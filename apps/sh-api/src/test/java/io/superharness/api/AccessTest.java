package io.superharness.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class AccessTest {
    @org.junit.jupiter.api.io.TempDir static java.nio.file.Path temp;
    @Container static final MySQLContainer<?> db = new MySQLContainer<>("mysql:8.4.13");

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", db::getJdbcUrl);
        r.add("spring.datasource.username", db::getUsername);
        r.add("spring.datasource.password", db::getPassword);
        r.add("SH_ARTIFACT_PATH", () -> temp.toString());
        r.add("SH_DEV_KEY", () -> java.util.Base64.getEncoder().encodeToString(new byte[48]));
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired io.superharness.persistence.Catalog catalog;
    @Autowired io.superharness.runengine.Runs runs;
    @Autowired io.superharness.runengine.Scheduler scheduler;
    @Autowired io.superharness.persistence.Sql sql;
    @Autowired io.superharness.persistence.EventJournal events;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Test
    void idempotentAdmissionSerializedClaimAndRollback() throws Exception {
        var p = new io.superharness.domain.Principal("local", "developer");
        var agent = catalog.createAgent(p, "test");
        var revision =
                catalog.publish(
                        p, agent, "test", "local-echo", "test-claim", 1, java.util.Map.of());
        var conversation = catalog.conversation(p, agent, revision);
        var a = runs.submit(p, conversation, agent, revision, "alpha", "a", 120);
        var duplicate = runs.submit(p, conversation, agent, revision, "alpha", "a", 120);
        org.junit.jupiter.api.Assertions.assertEquals(a.get("runId"), duplicate.get("runId"));
        org.junit.jupiter.api.Assertions.assertThrows(
                io.superharness.domain.Problem.class,
                () -> runs.submit(p, conversation, agent, revision, "changed", "a", 120));
        runs.submit(p, conversation, agent, revision, "beta", "b", 120);
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var claims =
                    pool.invokeAll(
                            java.util.List.<java.util.concurrent.Callable<Boolean>>of(
                                    () -> {
                                        barrier.await();
                                        return scheduler.claim("A", "test-claim", 1).isPresent();
                                    },
                                    () -> {
                                        barrier.await();
                                        return scheduler.claim("B", "test-claim", 1).isPresent();
                                    }));
            org.junit.jupiter.api.Assertions.assertNotEquals(
                    claims.get(0).get(), claims.get(1).get());
        }
        var id = java.util.UUID.fromString(a.get("runId").toString());
        var before = runs.status(p, id).get("lastSeq");
        new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                .executeWithoutResult(
                        tx -> {
                            events.append(p.tenant(), id, "probe.rollback", java.util.Map.of());
                            tx.setRollbackOnly();
                        });
        org.junit.jupiter.api.Assertions.assertEquals(before, runs.status(p, id).get("lastSeq"));
        org.junit.jupiter.api.Assertions.assertEquals(
                ((Number) before).longValue(),
                sql.count(
                        "SELECT COUNT(*) FROM outbox WHERE tenant_id=? AND run_id=?",
                        p.tenant(),
                        id));
    }

    @Autowired io.superharness.runengine.RunLifecycle lifecycle;

    @Test
    void staleAttemptCannotPublishAndGateSurvivesRecovery() throws Exception {
        var p = new io.superharness.domain.Principal("local", "developer");
        var agent = catalog.createAgent(p, "recovery");
        var revision =
                catalog.publish(
                        p, agent, "hello", "local-echo", "test-recovery", 1, java.util.Map.of());
        var conv = catalog.conversation(p, agent, revision);
        runs.submit(p, conv, agent, revision, "work", "recover", 100);
        var first = scheduler.claim("A", "test-recovery", 1).orElseThrow();
        lifecycle.start(first);
        lifecycle.delta(first, "原始");
        lifecycle.delta(first, "草稿");
        sql.update(
                "UPDATE agent_runs SET lease_deadline=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE"
                        + " id=?",
                first.run());
        org.junit.jupiter.api.Assertions.assertThrows(
                io.superharness.domain.Problem.class, () -> lifecycle.heartbeat(first));
        org.junit.jupiter.api.Assertions.assertTrue(lifecycle.reapOne());
        org.junit.jupiter.api.Assertions.assertEquals(
                "RECOVERY_REQUIRED", runs.status(p, first.run()).get("status"));
        lifecycle.resume(p, first.run(), 1);
        var next = scheduler.claim("B", "test-recovery", 1).orElseThrow();
        lifecycle.start(next);
        org.junit.jupiter.api.Assertions.assertThrows(
                io.superharness.domain.Problem.class, () -> lifecycle.finish(first, "stale", null));
        lifecycle.delta(next, "working");
        assertEquals(
                java.util.List.of("原始草稿", "working"),
                assistantText(threads.snapshot(p, conv, null).orElseThrow()));
        lifecycle.finish(next, "fresh", null);
        assertEquals(
                java.util.List.of("原始草稿", "fresh"),
                assistantText(threads.snapshot(p, conv, null).orElseThrow()));
        org.junit.jupiter.api.Assertions.assertEquals(
                next.turn().slot(),
                io.superharness.persistence.Sql.uuid(
                        sql.first("SELECT state_slot FROM conversations WHERE id=?", conv)
                                .orElseThrow()
                                .get("state_slot")));
        org.junit.jupiter.api.Assertions.assertEquals(
                0,
                sql.count("SELECT COUNT(*) FROM conversation_gates WHERE conversation_id=?", conv));
    }

    @Test
    void historyRetainsCommittedOutputAfterFailureAndCancellation() throws Exception {
        var p = new io.superharness.domain.Principal("local", "developer");
        var agent = catalog.createAgent(p, "partials");
        var revision =
                catalog.publish(
                        p, agent, "test", "local-echo", "test-partials", 1, java.util.Map.of());
        var conv = catalog.conversation(p, agent, revision);
        for (var status : java.util.List.of("FAILED", "CANCELLED")) {
            runs.submit(p, conv, agent, revision, status, status, 120);
            var task = scheduler.claim("worker", "test-partials", 1).orElseThrow();
            lifecycle.start(task);
            lifecycle.delta(task, "partial " + status);
            if (status.equals("CANCELLED")) lifecycle.cancel(p, task.run());
            lifecycle.finish(task, null, "MODEL_ERROR");
            var view = threads.snapshot(p, conv, null).orElseThrow();
            assertEquals(status, view.turns().getLast().get("status"));
            assertEquals("partial " + status, assistantText(view).getLast());
        }
    }

    @Autowired AguiThreads threads;

    private java.util.List<String> assistantText(AguiThreads.ThreadView view) {
        return view.messages().stream()
                .filter(m -> "assistant".equals(m.get("role")))
                .map(m -> m.get("content").toString())
                .toList();
    }

    @Autowired io.superharness.runengine.Approvals approvals;
    @Autowired io.superharness.runengine.Artifacts artifacts;

    @Test
    void approvedPublicationHasOneReceiptAcrossRepeatedDecision() throws Exception {
        var p = new io.superharness.domain.Principal("local", "developer");
        var agent = catalog.createAgent(p, "export");
        var revision =
                catalog.publish(
                        p,
                        agent,
                        "test",
                        "local-echo",
                        "test-export",
                        1,
                        java.util.Map.of("outputMode", "APPROVED_ARTIFACT"));
        var requester = new io.superharness.domain.Principal(p.tenant(), "export-member");
        sql.update(
                "INSERT INTO memberships(tenant_id,user_id,role) VALUES(?,?,'MEMBER')",
                requester.tenant(),
                requester.user());
        var conv = catalog.conversation(requester, agent, revision);
        org.junit.jupiter.api.Assertions.assertThrows(
                io.superharness.domain.Problem.class, () -> threads.snapshot(p, conv, null));
        runs.submit(requester, conv, agent, revision, "data", "export", 120);
        var task = scheduler.claim("A", "test-export", 1).orElseThrow();
        lifecycle.start(task);
        var staged = artifacts.stage(p.tenant(), "export result");
        lifecycle.awaitApproval(task, "export result", staged);
        org.junit.jupiter.api.Assertions.assertTrue(
                artifacts.list(requester, task.run()).isEmpty());
        var approval =
                approvals.pending(p).stream()
                        .filter(a -> task.run().toString().equals(a.get("runId")))
                        .findFirst()
                        .orElseThrow();
        assertEquals(true, approval.get("canDecide"));
        var id = java.util.UUID.fromString(approval.get("id").toString());
        assertEquals(1, catalog.conversations(requester, agent).size());
        assertEquals(0, catalog.conversations(p, agent).size());
        org.junit.jupiter.api.Assertions.assertThrows(
                io.superharness.domain.Problem.class,
                () -> catalog.updateConversation(p, conv, "other", false));
        org.junit.jupiter.api.Assertions.assertThrows(
                io.superharness.domain.Problem.class,
                () -> catalog.updateConversation(requester, conv, null, true));
        catalog.updateConversation(requester, conv, "renamed", false);
        assertEquals("renamed", catalog.conversations(requester, agent).getFirst().get("name"));
        var ownRequest = approvals.pending(requester).getFirst();
        assertEquals(approval.get("id"), ownRequest.get("id"));
        assertEquals(false, ownRequest.get("canDecide"));
        org.junit.jupiter.api.Assertions.assertThrows(
                io.superharness.domain.Problem.class,
                () -> approvals.decide(requester, id, approval.get("argsHash").toString(), true));
        org.junit.jupiter.api.Assertions.assertThrows(
                io.superharness.domain.Problem.class, () -> approvals.decide(p, id, "wrong", true));
        approvals.decide(p, id, approval.get("argsHash").toString(), true);
        approvals.decide(p, id, approval.get("argsHash").toString(), true);
        var list = artifacts.list(requester, task.run());
        org.junit.jupiter.api.Assertions.assertEquals(1, list.size());
        org.junit.jupiter.api.Assertions.assertArrayEquals(
                "export result".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                artifacts.download(
                        requester, java.util.UUID.fromString(list.get(0).get("id").toString())));
        org.junit.jupiter.api.Assertions.assertEquals(
                "SUCCEEDED", runs.status(requester, task.run()).get("status"));
        catalog.updateConversation(requester, conv, null, true);
        assertEquals(0, catalog.conversations(requester, agent).size());
        org.junit.jupiter.api.Assertions.assertThrows(
                io.superharness.domain.Problem.class,
                () -> runs.submit(requester, conv, agent, revision, "next", "next", 120));
    }

    @Autowired io.superharness.persistence.Knowledge knowledge;
    @Autowired io.superharness.persistence.TenantSettings tenants;

    @Test
    void revisionsPinSkillsAndMembershipControlsMemoryAndBudgets() {
        var owner = new io.superharness.domain.Principal("policy", "lead");
        var member = new io.superharness.domain.Principal("policy", "reader");
        sql.update("INSERT INTO tenants(id) VALUES(?)", owner.tenant());
        sql.update(
                "INSERT INTO memberships(tenant_id,user_id,role) VALUES(?,?,'OWNER')",
                owner.tenant(),
                owner.user());
        tenants.member(owner, "reader", "MEMBER", true);
        var note = knowledge.remember(owner, "private");
        org.junit.jupiter.api.Assertions.assertTrue(knowledge.memories(member).isEmpty());
        org.junit.jupiter.api.Assertions.assertThrows(
                io.superharness.domain.Problem.class, () -> knowledge.forget(member, note));
        org.junit.jupiter.api.Assertions.assertThrows(
                io.superharness.domain.Problem.class,
                () -> tenants.limits(member, 2, 10000, 1000, 1));
        org.junit.jupiter.api.Assertions.assertThrows(
                io.superharness.domain.Problem.class,
                () -> tenants.member(owner, "lead", "MEMBER", true));
        tenants.limits(owner, 4, 10000, 1000, 1);
        var skill = knowledge.publish(owner, "format", "1", "return text", "Format text", java.util.Map.of());
        var agent = catalog.createAgent(owner, "budget test");
        var revision =
                catalog.publish(
                        owner,
                        agent,
                        "test",
                        "local-echo",
                        "budget-test",
                        1,
                        java.util.Map.of(
                                "skills", knowledge.pin(owner.tenant(), java.util.List.of(skill))));
        var conversation = catalog.conversation(member, agent, revision);
        runs.submit(member, conversation, agent, revision, "first", "TEST", 120);
        knowledge.revoke(owner, skill);
        var e = scheduler.claim("test", "budget-test", 1).orElseThrow();
        assertEquals("return text", e.turn().skills().getFirst().content());
        assertEquals("test", e.turn().prompt());
        lifecycle.start(e);
        lifecycle.reserve(e, 100, 100);
        org.junit.jupiter.api.Assertions.assertThrows(
                io.superharness.domain.Problem.class, () -> lifecycle.reserve(e, 801, 1));
        lifecycle.readTool(e, "lookup");
        org.junit.jupiter.api.Assertions.assertThrows(
                io.superharness.domain.Problem.class, () -> lifecycle.readTool(e, "lookup"));
        lifecycle.finish(e, "done", null);
        // Identical request can still retrieve the accepted receipt after policy changed.
        runs.submit(member, conversation, agent, revision, "first", "TEST", 120);
        org.junit.jupiter.api.Assertions.assertThrows(
                io.superharness.domain.Problem.class,
                () -> runs.submit(member, conversation, agent, revision, "first", "test", 120));
        tenants.member(owner, "reader", "MEMBER", false);
        org.junit.jupiter.api.Assertions.assertThrows(
                io.superharness.domain.Problem.class, () -> runs.status(member, e.run()));
        org.junit.jupiter.api.Assertions.assertFalse(tenants.audit(owner).isEmpty());
    }

    @Test
    void interactionReplaysMultiplePagesAndCorrectsOnlyTheHistoricalTurn() throws Exception {
        var owner = new io.superharness.domain.Principal("local", "developer");
        var agent = catalog.createAgent(owner, "agui replay");
        var revision =
                catalog.publish(
                        owner, agent, "test", "local-echo", "agui-pages", 1, java.util.Map.of());
        var thread = catalog.conversation(owner, agent, revision);
        runs.submitInteraction(owner, thread, "first", "user-first", "original");
        var e = scheduler.claim("worker", "agui-pages", 1).orElseThrow();
        lifecycle.start(e);
        for (int n = 0; n < 130; n++) lifecycle.delta(e, "试");
        lifecycle.finish(e, "canonical", null);
        runs.submitInteraction(owner, thread, "next", "user-next", "later input");
        var request =
                post("/api/v1/ag-ui/runs")
                        .contentType("application/json")
                        .accept("text/event-stream")
                        .content(
                                mapper.writeValueAsBytes(
                                        java.util.Map.of(
                                                "threadId",
                                                thread,
                                                "runId",
                                                "first",
                                                "messages",
                                                java.util.List.of(
                                                        java.util.Map.of(
                                                                "id",
                                                                "user-first",
                                                                "role",
                                                                "user",
                                                                "content",
                                                                "original")))));
        mvc.perform(request).andExpect(status().isUnauthorized());
        var wrong = jwt().jwt(t -> t.subject("developer").claim("tenant_id", "other"));
        mvc.perform(request.with(wrong)).andExpect(status().isForbidden());
        var member = "agui-reader";
        tenants.member(owner, member, "MEMBER", true);
        mvc.perform(request.with(jwt().jwt(t -> t.subject(member).claim("tenant_id", "local"))))
                .andExpect(status().isNotFound());
        long started = System.nanoTime();
        var r =
                mvc.perform(
                                request.with(
                                        jwt().jwt(
                                                        t ->
                                                                t.subject("developer")
                                                                        .claim("tenant_id", "local")
                                                                        .expiresAt(
                                                                                java.time.Instant
                                                                                        .now()
                                                                                        .plusSeconds(
                                                                                                60)))))
                        .andExpect(request().asyncStarted())
                        .andReturn();
        r.getAsyncResult(10000);
        var frames = new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        for (String line :
                r.getResponse()
                        .getContentAsString(java.nio.charset.StandardCharsets.UTF_8)
                        .lines()
                        .toList())
            if (line.startsWith("data: ")) frames.add(mapper.readTree(line.substring(6)));
        assertEquals(
                130L,
                frames.stream()
                        .filter(f -> f.path("type").asText().equals("TEXT_MESSAGE_CONTENT"))
                        .count());
        var snapshot =
                frames.stream()
                        .filter(f -> f.path("type").asText().equals("MESSAGES_SNAPSHOT"))
                        .findFirst()
                        .orElseThrow()
                        .path("messages");
        assertEquals(2, snapshot.size());
        assertEquals("user-first", snapshot.get(0).path("id").asText());
        assertEquals("canonical", snapshot.get(1).path("content").asText());
        assertEquals("RUN_FINISHED", frames.getLast().path("type").asText());
        System.out.printf(
                "AG-UI replay: %d ledger rows, 2 data pages, %d ms%n",
                ((Number) runs.status(owner, e.run()).get("lastSeq")).longValue(),
                (System.nanoTime() - started) / 1_000_000);
    }

    @Test
    void expiredApprovalCannotPublishAndAClosedSubscriptionCanReplay() throws Exception {
        var p = new io.superharness.domain.Principal("local", "developer");
        var agent = catalog.createAgent(p, "approval expiry");
        var revision =
                catalog.publish(
                        p,
                        agent,
                        "test",
                        "local-echo",
                        "agui-expiry",
                        1,
                        java.util.Map.of("outputMode", "APPROVED_ARTIFACT"));
        var thread = catalog.conversation(p, agent, revision);
        runs.submitInteraction(p, thread, "turn", "input", "text");
        var e = scheduler.claim("worker", "agui-expiry", 1).orElseThrow();
        lifecycle.start(e);
        lifecycle.awaitApproval(e, "result", artifacts.stage(p.tenant(), "result"));
        var approval =
                approvals.pending(p).stream()
                        .filter(a -> e.run().toString().equals(a.get("runId")))
                        .findFirst()
                        .orElseThrow();
        var aid = java.util.UUID.fromString((String) approval.get("id"));
        sql.update(
                "UPDATE approvals SET expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",
                aid);
        var body =
                java.util.Map.of(
                        "threadId",
                        thread,
                        "runId",
                        "decision",
                        "resume",
                        java.util.List.of(
                                java.util.Map.of(
                                        "interruptId",
                                        aid,
                                        "status",
                                        "resolved",
                                        "payload",
                                        java.util.Map.of(
                                                "approved",
                                                true,
                                                "argsHash",
                                                approval.get("argsHash")))));
        var request =
                post("/api/v1/ag-ui/runs")
                        .contentType("application/json")
                        .accept("text/event-stream")
                        .content(mapper.writeValueAsBytes(body))
                        .with(
                                jwt().jwt(
                                                t ->
                                                        t.subject("developer")
                                                                .claim("tenant_id", "local")
                                                                .expiresAt(
                                                                        java.time.Instant.now()
                                                                                .plusSeconds(60))));
        var response = mvc.perform(request).andExpect(request().asyncStarted()).andReturn();
        response.getAsyncResult(10000);
        org.junit.jupiter.api.Assertions.assertTrue(
                response.getResponse().getContentAsString().contains("APPROVAL_CLOSED"));
        assertEquals("WAITING_APPROVAL", runs.status(p, e.run()).get("status"));
        assertEquals(0, artifacts.list(p, e.run()).size());
        sql.update(
                "UPDATE agent_runs SET deadline_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",
                e.run());
        lifecycle.reapOne();
        assertEquals("DEADLINE_EXCEEDED", runs.status(p, e.run()).get("errorCode"));

        var queued = runs.submitInteraction(p, thread, "cancel", "cancel-message", "cancel me");
        var queuedId = java.util.UUID.fromString(queued.get("runId").toString());
        var jwt =
                org.springframework.security.oauth2.jwt.Jwt.withTokenValue("fixture")
                        .header("alg", "none")
                        .subject("developer")
                        .claim("tenant_id", "local")
                        .issuedAt(java.time.Instant.now().minusSeconds(10))
                        .expiresAt(java.time.Instant.now().minusSeconds(1))
                        .build();
        var out = new java.io.ByteArrayOutputStream();
        RunStream.tail(
                runs,
                p,
                queuedId,
                0,
                jwt,
                out,
                new org.springframework.mock.web.MockHttpServletResponse(),
                event -> {
                    throw new AssertionError("Expired subscription delivered events");
                });
        assertEquals("QUEUED", runs.status(p, queuedId).get("status"));
        lifecycle.cancel(p, queuedId);
        var cancelled =
                mvc.perform(
                                post("/api/v1/ag-ui/runs")
                                        .contentType("application/json")
                                        .content(
                                                mapper.writeValueAsBytes(
                                                        java.util.Map.of(
                                                                "threadId",
                                                                thread,
                                                                "runId",
                                                                "cancel",
                                                                "messages",
                                                                java.util.List.of(
                                                                        java.util.Map.of(
                                                                                "id",
                                                                                "cancel-message",
                                                                                "role",
                                                                                "user",
                                                                                "content",
                                                                                "cancel me")))))
                                        .with(
                                                jwt().jwt(
                                                                t ->
                                                                        t.subject("developer")
                                                                                .claim(
                                                                                        "tenant_id",
                                                                                        "local")
                                                                                .expiresAt(
                                                                                        java.time
                                                                                                .Instant
                                                                                                .now()
                                                                                                .plusSeconds(
                                                                                                        60)))))
                        .andExpect(request().asyncStarted())
                        .andReturn();
        cancelled.getAsyncResult(10000);
        org.junit.jupiter.api.Assertions.assertTrue(
                cancelled.getResponse().getContentAsString().contains("CANCELLED"));
    }

    @Test
    void missingIdentityAndCrossTenantAccessAreDenied() throws Exception {
        mvc.perform(get("/api/v1/agents")).andExpect(status().isUnauthorized());
        var owner = jwt().jwt(t -> t.subject("developer").claim("tenant_id", "local"));
        var other = jwt().jwt(t -> t.subject("developer").claim("tenant_id", "other"));
        var result =
                mvc.perform(
                                post("/api/v1/agents")
                                        .with(owner)
                                        .contentType("application/json")
                                        .content("{\"name\":\"support\"}"))
                        .andExpect(status().isOk())
                        .andReturn();
        var id =
                mapper.readTree(result.getResponse().getContentAsByteArray())
                        .path("agentId")
                        .asText();
        mvc.perform(get("/api/v1/agents/" + id).with(other)).andExpect(status().isForbidden());
        mvc.perform(
                        post("/api/v1/agents/" + id + "/revisions")
                                .with(owner)
                                .contentType("application/json")
                                .content("{\"prompt\":\"Hello\",\"model\":\"local-echo\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/agents/" + id + "/revisions").with(owner))
                .andExpect(jsonPath("$[0].revision").value(1));
    }
}
