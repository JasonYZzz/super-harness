package io.superharness.runengine;

import io.superharness.domain.*;
import io.superharness.persistence.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.CancellationException;

@Service
public class RunLifecycle {
    private final Knowledge knowledge;
    private final Sql sql;
    private final EventJournal journal;
    private final Catalog catalog;

    private final DocumentLibraries libraries;

    public RunLifecycle(Knowledge knowledge, Sql sql, EventJournal journal, Catalog catalog, DocumentLibraries libraries) {
        this.libraries=libraries;
        this.knowledge = knowledge;
        this.sql = sql;
        this.journal = journal;
        this.catalog = catalog;
    }

    private Map<String, Object> lock(String tenant, UUID run) {
        return sql.first(
                        "SELECT * FROM agent_runs WHERE tenant_id=? AND id=? FOR UPDATE",
                        tenant,
                        run)
                .orElseThrow(Problem::missing);
    }

    private Map<String, Object> owned(Execution e) {
        var row = lock(e.tenant(), e.run());
        // Evaluate the clock after acquisition, even when the lock was contended.
        if (sql.count(
"""
SELECT COUNT(*) FROM agent_runs WHERE tenant_id=? AND id=? AND lease_owner=? AND fence=?
  AND status IN ('CLAIMED','RUNNING') AND lease_deadline>UTC_TIMESTAMP(6)
""",
                        e.tenant(),
                        e.run(),
                        e.owner(),
                        e.fence())
                != 1) throw new Problem(409, "LEASE_LOST");
        return row;
    }

    private boolean cancelRequested(Map<String, Object> row) {
        return Boolean.TRUE.equals(row.get("cancel_requested"));
    }

    private boolean overdue(String tenant, UUID run) {
        return sql.count(
                        "SELECT COUNT(*) FROM agent_runs WHERE tenant_id=? AND id=? AND"
                                + " deadline_at<=UTC_TIMESTAMP(6)",
                        tenant,
                        run)
                > 0;
    }

    @Transactional
    public void start(Execution e) {
        var row = owned(e);
        if (cancelRequested(row) || overdue(e.tenant(), e.run())) throw new CancellationException();
        if (sql.update(
                        "UPDATE agent_runs SET status='RUNNING' WHERE tenant_id=? AND id=? AND"
                                + " status='CLAIMED'",
                        e.tenant(),
                        e.run())
                != 1) throw new Problem(409, "ALREADY_STARTED");
        journal.append(e.tenant(), e.run(), "run.started", Map.of());
    }

    @Transactional
    public boolean heartbeat(Execution e) {
        var row = owned(e);
        if (cancelRequested(row) || overdue(e.tenant(), e.run())) return false;
        sql.update(
                "UPDATE agent_runs SET lease_deadline=UTC_TIMESTAMP(6)+INTERVAL 60 SECOND WHERE"
                        + " tenant_id=? AND id=?",
                e.tenant(),
                e.run());
        return true;
    }

    @Transactional
    public UUID reserve(Execution e, int input, int output) {
        return reserve(e, input, output, false);
    }

    @Transactional
    public UUID reserve(Execution e, int input, int output, boolean webSearchRequested) {
        var row = owned(e);
        if (cancelRequested(row) || overdue(e.tenant(), e.run())) throw new CancellationException();
        int reserved = Math.addExact(input, output);
        var limits =
                sql.first(
                                "SELECT daily_token_budget,run_token_budget FROM tenants WHERE id=?"
                                        + " FOR UPDATE",
                                e.tenant())
                        .orElseThrow();
        long daily =
                sql.count(
                        "SELECT COALESCE(SUM(COALESCE(input_tokens+output_tokens,reserved)),0) FROM"
                                + " model_usage WHERE tenant_id=? AND created_at>=UTC_DATE()",
                        e.tenant());
        long used =
                sql.count(
                        "SELECT COALESCE(SUM(COALESCE(input_tokens+output_tokens,reserved)),0) FROM"
                                + " model_usage WHERE tenant_id=? AND run_id=?",
                        e.tenant(),
                        e.run());
        if (reserved > 64000
                || used + reserved > ((Number) limits.get("run_token_budget")).longValue()
                || daily + reserved > ((Number) limits.get("daily_token_budget")).longValue())
            throw new Problem(429, "MODEL_BUDGET_EXCEEDED");
        var call = UUID.randomUUID();
        sql.update(
"""
INSERT INTO model_usage(id,tenant_id,run_id,attempt,model,reserved,currency,input_per_million,output_per_million)
SELECT ?,?,?,?, ?,?,JSON_UNQUOTE(JSON_EXTRACT(cfg,'$.pricing.currency')),
  JSON_UNQUOTE(JSON_EXTRACT(cfg,'$.pricing.inputPerMillion')), JSON_UNQUOTE(JSON_EXTRACT(cfg,'$.pricing.outputPerMillion'))
FROM (SELECT COALESCE(r.input_json->'$.modelOverride',a.config) AS cfg
      FROM agent_runs r JOIN agent_revisions a ON a.id=r.agent_revision_id AND a.tenant_id=r.tenant_id
      WHERE r.tenant_id=? AND r.id=?) AS snapshot
""",
                call,
                e.tenant(),
                e.run(),
                e.attempt(),
                e.turn().model(),
                reserved,
                e.tenant(),
                e.run());
        journal.append(
                e.tenant(),
                e.run(),
                "model.started",
                Map.of(
                        "model",
                        e.turn().model(),
                        "callId",
                        call,
                        "reserved",
                        reserved,
                        "webSearchRequested",
                        webSearchRequested));
        return call;
    }

    @Transactional
    public void record(Execution e, UUID call, int input, int output) {
        owned(e);
        if (input < 0 || output < 0) throw new IllegalStateException("Negative reported usage");
        sql.update(
                "UPDATE model_usage SET input_tokens=?,output_tokens=? WHERE tenant_id=? AND"
                        + " run_id=? AND id=?",
                input,
                output,
                e.tenant(),
                e.run(),
                call);
        journal.append(
                e.tenant(),
                e.run(),
                "usage.updated",
                Map.of("callId", call, "input", input, "output", output));
    }

    @Transactional
    public void plan(Execution e, List<Map<String, Object>> tasks) {
        owned(e);
        journal.append(e.tenant(), e.run(), "plan.updated", Map.of("tasks", tasks));
    }

    @Transactional
    public void readTool(Execution e, String name) {
        var row = owned(e);
        if (cancelRequested(row) || overdue(e.tenant(), e.run())) throw new CancellationException();
        if (sql.count(
                        "SELECT COUNT(*) FROM run_events WHERE tenant_id=? AND run_id=? AND"
                                + " event_type='tool.started'",
                        e.tenant(),
                        e.run())
                >= sql.count("SELECT run_tool_limit FROM tenants WHERE id=?", e.tenant()))
            throw new Problem(429, "TOOL_QUOTA_EXCEEDED");
        journal.append(
                e.tenant(),
                e.run(),
                "tool.started",
                Map.of(
                        "name",
                        name,
                        "mode",
                        e.turn().mcp() != null && e.turn().mcp().readOnlyTools().contains(name)
                                ? "READ_ONLY"
                                : "LOCAL_TOOL"));
    }

    @Transactional
    public void stageMemory(
            Execution e, List<String> facts, String summary, java.util.List<String> legacy) {
        owned(e);
        if (!e.turn().memoryEnabled()) throw new IllegalStateException("Memory disabled");
        var pending = new LinkedHashMap<String, Object>();
        pending.put("facts", facts);
        pending.put("legacy", legacy);
        pending.put("summary", summary);
        pending.put("summaryId", e.turn().recall().summaryId());
        sql.update(
                "UPDATE agent_runs SET pending_memory=? WHERE tenant_id=? AND id=?",
                sql.json(pending),
                e.tenant(),
                e.run());
    }

    @Transactional
    public List<String> searchSession(Execution e, String query, int limit) {
        var row = owned(e);
        if (!e.turn().memoryEnabled()) throw new IllegalStateException("Memory disabled");
        return sql
                .rows(
                        """
                        SELECT LEFT(CONCAT(r.input_json->>'$.text',CHAR(10),ev.payload->>'$.text'),4000) AS excerpt
                        FROM agent_runs r JOIN run_events ev ON ev.tenant_id=r.tenant_id AND ev.run_id=r.id
                            AND ev.event_type='run.completed'
                        WHERE r.tenant_id=? AND r.requested_by=? AND r.conversation_id=? AND r.status='SUCCEEDED'
                            AND (LOCATE(LOWER(?),LOWER(r.input_json->>'$.text'))>0
                              OR LOCATE(LOWER(?),LOWER(ev.payload->>'$.text'))>0)
                        ORDER BY r.accepted_at DESC,r.id LIMIT ?
                        """,
                        e.tenant(),
                        row.get("requested_by"),
                        e.turn().conversation(),
                        query,
                        query,
                        limit)
                .stream()
                .map(entry -> Objects.toString(entry.get("excerpt"), ""))
                .toList();
    }

    @Transactional
    public String searchDocuments(Execution e,String query) {
        var row=owned(e);
        if(e.turn().libraryId()==null) throw Problem.missing();
        var principal=new Principal(e.tenant(),row.get("requested_by").toString());
        return sql.json(libraries.search(principal,e.turn().libraryId(),query));
    }

    @Transactional
    public String legacyFile(Execution e, String path) {
        var row = owned(e);
        if (!e.turn().memoryEnabled()) throw new IllegalStateException("Memory disabled");
        return knowledge.legacyFile(
                e.tenant(), row.get("requested_by").toString(), e.turn().conversation(), path);
    }

    public boolean publishedSlot(Execution e, UUID slot) {
        return sql.count(
                        "SELECT COUNT(*) FROM agent_runs WHERE tenant_id=? AND conversation_id=?"
                                + " AND slot_id=? AND status='SUCCEEDED'",
                        e.tenant(),
                        e.turn().conversation(),
                        slot)
                > 0;
    }

    @Transactional
    public List<String> searchMemory(Execution e, String query) {
        var row = owned(e);
        if (!e.turn().memoryEnabled()) throw new IllegalStateException("Memory disabled");
        return knowledge.recall(
                e.tenant(), row.get("requested_by").toString(), e.turn().conversation(), query, 12);
    }

    @Transactional
    public void delta(Execution e, String text) {
        var row = owned(e);
        if (cancelRequested(row) || overdue(e.tenant(), e.run())) throw new CancellationException();
        journal.append(e.tenant(), e.run(), "text.delta", Map.of("text", text));
    }

    @Transactional
    public void finish(Execution e, String result, String error) {
        var row = owned(e);
        if (cancelRequested(row)) {
            terminal(row, "CANCELLED", "run.cancelled", null, Map.of());
            return;
        }
        if (overdue(e.tenant(), e.run())) error = "DEADLINE_EXCEEDED";
        if (error != null) {
            terminal(row, "FAILED", "run.failed", error, Map.of("code", error));
            return;
        }
        knowledge.publishMemory(row);
        sql.update(
                "UPDATE conversations SET state_slot=? WHERE tenant_id=? AND id=?",
                e.turn().slot(),
                e.tenant(),
                e.turn().conversation());
        sql.update(
"""
INSERT INTO run_checkpoints(id,tenant_id,run_id,attempt,boundary,state_ref,safe_to_replay)
VALUES(?,?,?,?,'TURN_COMPLETED',?,TRUE)
""",
                UUID.randomUUID(),
                e.tenant(),
                e.run(),
                e.attempt(),
                e.turn().slot().toString());
        journal.append(
                e.tenant(), e.run(), "checkpoint.created", Map.of("boundary", "TURN_COMPLETED"));
        terminal(row, "SUCCEEDED", "run.completed", null, Map.of("text", result));
    }

    @Transactional
    public void awaitApproval(Execution e, String result, Artifacts.Staged staged) {
        var row = owned(e);
        if (cancelRequested(row) || overdue(e.tenant(), e.run())) {
            finish(e, null, "DEADLINE_EXCEEDED");
            return;
        }
        UUID tool = UUID.randomUUID(), approval = UUID.randomUUID();
        String hash =
                sql.digest(
                        Map.of("tool", "publish_text", "version", "1", "sha256", staged.sha256()));
        String args =
                sql.json(
                        Map.of(
                                "key",
                                staged.key(),
                                "sha256",
                                staged.sha256(),
                                "size",
                                staged.size(),
                                "text",
                                result));
        sql.update(
"""
INSERT INTO tool_executions(id,tenant_id,run_id,logical_step_id,tool_name,tool_version,args_sha256,
  idempotency_key,status,result_ref) VALUES(?,?,?,'final-export','publish_text','1',?,?,'PENDING',?)
""",
                tool,
                e.tenant(),
                e.run(),
                hash,
                e.run() + "/final-export/1",
                args);
        sql.update(
"""
INSERT INTO approvals(id,tenant_id,run_id,tool_execution_id,requested_args_sha256,status,requested_by,expires_at)
SELECT ?,tenant_id,id,?,?,'PENDING',requested_by,deadline_at FROM agent_runs WHERE tenant_id=? AND id=?
""",
                approval,
                tool,
                hash,
                e.tenant(),
                e.run());
        sql.update(
"""
INSERT INTO run_checkpoints(id,tenant_id,run_id,attempt,boundary,state_ref,safe_to_replay)
VALUES(?,?,?,?,'WAITING_APPROVAL',?,TRUE)
""",
                UUID.randomUUID(),
                e.tenant(),
                e.run(),
                e.attempt(),
                e.turn().slot().toString());
        sql.update(
"""
UPDATE agent_runs SET status='WAITING_APPROVAL',fence=fence+1,lease_owner=NULL,lease_deadline=NULL
WHERE tenant_id=? AND id=?
""",
                e.tenant(),
                e.run());
        journal.append(
                e.tenant(),
                e.run(),
                "tool.requested",
                Map.of("tool", "publish_text", "version", "1", "toolCallId", tool));
        journal.append(
                e.tenant(),
                e.run(),
                "approval.required",
                Map.of("approvalId", approval, "argsHash", hash, "toolCallId", tool));
    }

    private void terminal(
            Map<String, Object> row, String status, String event, String error, Object payload) {
        String tenant = (String) row.get("tenant_id");
        var run = Sql.uuid(row.get("id"));
        sql.update(
                """
                UPDATE agent_runs SET pending_memory=NULL,status=?,error_code=?,lease_owner=NULL,lease_deadline=NULL,
                  finished_at=UTC_TIMESTAMP(6),fence=fence+1 WHERE tenant_id=? AND id=?
                """,
                status,
                error,
                tenant,
                run);
        sql.update(
                "DELETE FROM conversation_gates WHERE tenant_id=? AND conversation_id=? AND"
                        + " run_id=?",
                tenant,
                row.get("conversation_id"),
                run);
        if ("WAITING_APPROVAL".equals(row.get("status"))) {
            sql.update(
                    "UPDATE approvals SET status='CLOSED' WHERE tenant_id=? AND run_id=? AND"
                            + " status='PENDING'",
                    tenant,
                    run);
            sql.update(
                    "UPDATE tool_executions SET status='CLOSED' WHERE tenant_id=? AND run_id=? AND"
                            + " status='PENDING'",
                    tenant,
                    run);
        }
        journal.append(tenant, run, event, payload);
    }

    public static boolean terminalStatus(Object status) {
        return Set.of("FAILED", "SUCCEEDED", "CANCELLED").contains(status);
    }

    @Transactional
    public void cancelApproval(Principal p, UUID run, UUID approval) {
        var row = lock(p.tenant(), run);
        if (!p.user().equals(row.get("requested_by"))) throw Problem.missing();
        if (terminalStatus(row.get("status"))) return;
        if (!"WAITING_APPROVAL".equals(row.get("status"))
                || sql.first(
                                """
                                SELECT id FROM approvals WHERE tenant_id=? AND run_id=? AND id=? AND status='PENDING'
                                AND expires_at>UTC_TIMESTAMP(6) FOR UPDATE
                                """,
                                p.tenant(),
                                run,
                                approval)
                        .isEmpty()) throw new Problem(409, "APPROVAL_CLOSED");
        cancel(p, run);
    }

    @Transactional
    public void withdraw(Principal p, UUID run) {
        catalog.role(p);
        var row=lock(p.tenant(),run);
        if (!p.user().equals(row.get("requested_by"))) throw Problem.missing();
        if ("CANCELLED".equals(row.get("status"))) return;
        if (!"QUEUED".equals(row.get("status"))) throw new Problem(409,"RUN_ALREADY_STARTED");
        cancel(p,run);
    }

    @Transactional
    public void cancel(Principal p, UUID run) {
        catalog.role(p);
        var row = lock(p.tenant(), run);
        if (!p.user().equals(row.get("requested_by"))) throw Problem.missing();
        if (terminalStatus(row.get("status")) || cancelRequested(row)) return;
        if (hasUnknownTool(p.tenant(), run)) throw new Problem(409, "TOOL_RECONCILIATION_REQUIRED");
        sql.update(
                "UPDATE agent_runs SET cancel_requested=TRUE WHERE tenant_id=? AND id=?",
                p.tenant(),
                run);
        if (Set.of("RUNNING", "CLAIMED").contains(row.get("status")))
            journal.append(p.tenant(), run, "run.cancel_requested", Map.of());
        else terminal(row, "CANCELLED", "run.cancelled", null, Map.of());
    }

    private boolean hasUnknownTool(String tenant, UUID run) {
        return sql.count(
                        "SELECT COUNT(*) FROM tool_executions WHERE tenant_id=? AND run_id=? AND"
                                + " status IN ('EXECUTING','UNKNOWN')",
                        tenant,
                        run)
                > 0;
    }

    @Transactional
    public void resume(Principal p, UUID run, int attempt) {
        catalog.role(p);
        var r = lock(p.tenant(), run);
        if (!p.user().equals(r.get("requested_by"))) throw Problem.missing();
        // The recovery acknowledgement belongs to the failed attempt, even after it is retried.
        if (sql.count(
                        "SELECT COUNT(*) FROM run_events WHERE tenant_id=? AND run_id=? AND"
                            + " attempt=? AND event_type='run.recovering'",
                        p.tenant(),
                        run,
                        attempt)
                > 0) return;
        if (((Number) r.get("attempt")).intValue() != attempt)
            throw new Problem(409, "STALE_RECOVERY_ATTEMPT");
        if (!"RECOVERY_REQUIRED".equals(r.get("status")) || hasUnknownTool(p.tenant(), run))
            throw new Problem(409, "NO_SAFE_RETRY");
        if (overdue(p.tenant(), run)) throw new Problem(409, "DEADLINE_EXCEEDED");
        sql.update(
                "UPDATE agent_runs SET status='QUEUED',error_code=NULL WHERE tenant_id=? AND id=?",
                p.tenant(),
                run);
        journal.append(p.tenant(), run, "run.recovering", Map.of("actor", p.user()));
    }

    @Transactional
    public boolean reapOne() {
        var found =
                sql.first(
                        """
                        SELECT * FROM agent_runs WHERE
                         (status IN ('CLAIMED','RUNNING') AND lease_deadline<=UTC_TIMESTAMP(6)) OR
                         (status IN ('QUEUED','WAITING_APPROVAL') AND deadline_at<=UTC_TIMESTAMP(6))
                         ORDER BY updated_at,id LIMIT 1 FOR UPDATE SKIP LOCKED
                        """);
        if (found.isEmpty()) return false;
        var row = found.get();
        var tenant = row.get("tenant_id").toString();
        var id = Sql.uuid(row.get("id"));
        if (!hasUnknownTool(tenant, id)) {
            if (cancelRequested(row)) {
                terminal(row, "CANCELLED", "run.cancelled", null, Map.of());
                return true;
            }
            if (overdue(tenant, id)) {
                terminal(
                        row,
                        "FAILED",
                        "run.failed",
                        "DEADLINE_EXCEEDED",
                        Map.of("code", "DEADLINE_EXCEEDED"));
                return true;
            }
        }
        sql.update(
                """
                UPDATE agent_runs SET pending_memory=NULL,status='RECOVERY_REQUIRED',error_code='LEASE_EXPIRED',
                  fence=fence+1,lease_deadline=NULL,lease_owner=NULL WHERE tenant_id=? AND id=?
                """,
                tenant,
                id);
        journal.append(tenant, id, "run.recovery_required", Map.of("reason", "LEASE_EXPIRED"));
        return true;
    }
}
