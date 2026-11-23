package io.superharness.runengine;

import io.superharness.domain.*;
import io.superharness.persistence.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class Runs {
    private final Sql sql;
    private final Catalog catalog;
    private final EventJournal journal;

    public Runs(Sql sql, Catalog catalog, EventJournal journal) {
        this.sql = sql;
        this.catalog = catalog;
        this.journal = journal;
    }

    @Transactional
    public Map<String, Object> submit(
            Principal p,
            UUID conversation,
            UUID agent,
            UUID revision,
            String input,
            String key,
            int timeout) {
        return admit(p, conversation, agent, revision, input, key, timeout, null, null);
    }

    @Transactional
    public Map<String, Object> submitInteraction(
            Principal p, UUID thread, String runId, String messageId, String text) {
        String key = interactionKey(thread, runId);
        return admit(p, thread, null, null, text, key, 1200, messageId, runId);
    }

    public String interactionKey(UUID thread, String runId) {
        return "agui-" + sql.digest(List.of(thread, runId));
    }

    private Map<String, Object> admit(
            Principal p,
            UUID conversation,
            UUID agent,
            UUID revision,
            String input,
            String key,
            int timeout,
            String messageId,
            String interactionId) {
        catalog.role(p);
        if (key == null || key.isBlank() || key.length() > 180)
            throw new Problem(400, "INVALID_IDEMPOTENCY_KEY");
        // Serialize admission for this tenant so both the idempotency key and open-run quota are
        // exact.
        long limit =
                sql.count("SELECT max_open_runs FROM tenants WHERE id=? FOR UPDATE", p.tenant());
        var conv =
                sql.first(
                                "SELECT"
                                    + " agent_id,revision_id,model_override,permission_mode,CAST(archived"
                                    + " AS UNSIGNED) AS archived FROM conversations WHERE"
                                    + " tenant_id=? AND id=? AND owner_id=?",
                                p.tenant(),
                                conversation,
                                p.user())
                        .orElseThrow(Problem::missing);
        var pinned = Sql.uuid(conv.get("revision_id"));
        if ((revision != null && !revision.equals(pinned))
                || (agent != null && !agent.equals(Sql.uuid(conv.get("agent_id")))))
            throw new Problem(409, "CONVERSATION_REVISION_MISMATCH");
        String hash = sql.digest(List.of(conversation, pinned, input, timeout));
        var existing =
                sql.first(
                        "SELECT id,request_sha256 FROM agent_runs WHERE tenant_id=? AND"
                                + " requested_by=? AND request_key=? FOR UPDATE",
                        p.tenant(),
                        p.user(),
                        key);
        if (existing.isPresent()) {
            if (!hash.equals(existing.get().get("request_sha256")))
                throw new Problem(409, "IDEMPOTENCY_KEY_REUSED");
            return status(p, Sql.uuid(existing.get().get("id")));
        }
        if (sql.first(
                        """
                        SELECT id FROM agent_runs WHERE tenant_id=? AND conversation_id=?
                        AND status='WAITING_APPROVAL' LIMIT 1 FOR UPDATE
                        """,
                        p.tenant(),
                        conversation)
                .isPresent()) throw new Problem(409, "INTERRUPT_PENDING");
        if (((Number) conv.get("archived")).intValue() != 0)
            throw new Problem(409, "CONVERSATION_ARCHIVED");
        var revisionConfig =
                sql.first(
                                "SELECT config FROM agent_revisions WHERE tenant_id=? AND id=?",
                                p.tenant(),
                                pinned)
                        .orElseThrow();
        for (var skill : sql.parse(revisionConfig.get("config")).path("skills")) {
            UUID skillId = UUID.fromString(skill.path("id").asText());
            if (sql.first(
                            "SELECT id FROM skill_versions WHERE tenant_id=? AND id=? AND"
                                    + " revoked=FALSE FOR SHARE",
                            p.tenant(),
                            skillId)
                    .isEmpty()) throw new Problem(409, "SKILL_REVOKED");
        }
        if (sql.count(
                        "SELECT COUNT(*) FROM agent_runs WHERE tenant_id=? AND status NOT IN"
                                + " ('SUCCEEDED','FAILED','CANCELLED')",
                        p.tenant())
                >= limit) throw new Problem(429, "OPEN_RUN_LIMIT");
        var context = io.opentelemetry.api.trace.Span.current().getSpanContext();
        String traceparent =
                context.isValid()
                        ? "00-"
                                + context.getTraceId()
                                + "-"
                                + context.getSpanId()
                                + "-"
                                + context.getTraceFlags().asHex()
                        : null;
        var inputData = new LinkedHashMap<String, Object>();
        inputData.put("text", input);
        if (messageId != null) {
            inputData.put("messageId", messageId);
            inputData.put("interactionId", interactionId);
        }
        inputData.put("permissionMode", conv.get("permission_mode"));
        if (conv.get("model_override") != null)
            inputData.put("modelOverride", sql.parse(conv.get("model_override")));
        var id = UUID.randomUUID();
        sql.update(
                """
                INSERT INTO agent_runs(id,tenant_id,conversation_id,agent_revision_id,status,request_key,
                  request_sha256,requested_by,input_json,traceparent,deadline_at)
                VALUES(?,?,?,?,'QUEUED',?,?,?,?,?,TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(6)))
                """,
                id,
                p.tenant(),
                conversation,
                pinned,
                key,
                hash,
                p.user(),
                sql.json(inputData),
                traceparent,
                timeout);
        journal.append(p.tenant(), id, "run.accepted", Map.of());
        return status(p, id);
    }

    public Map<String, Object> status(Principal p, UUID id) {
        catalog.role(p);
        return sql.first(
                        """
                        SELECT BIN_TO_UUID(id) AS runId,BIN_TO_UUID(conversation_id) AS conversationId,
                          BIN_TO_UUID(agent_revision_id) AS agentRevisionId,status,attempt,next_seq AS lastSeq,
                          cancel_requested AS cancelRequested,error_code AS errorCode,accepted_at AS acceptedAt
                        FROM agent_runs WHERE tenant_id=? AND id=? AND requested_by=?
                        """,
                        p.tenant(),
                        id,
                        p.user())
                .orElseThrow(Problem::missing);
    }

    public List<Map<String, Object>> recent(Principal p) {
        catalog.role(p);
        return sql.rows(
                """
                SELECT BIN_TO_UUID(r.id) AS runId,BIN_TO_UUID(r.conversation_id) AS conversationId,
                  BIN_TO_UUID(c.agent_id) AS agentId,BIN_TO_UUID(r.agent_revision_id) AS agentRevisionId,
                  LEFT(r.input_json->>'$.text',80) AS inputPreview,r.next_seq AS lastSeq,r.status,r.attempt,r.accepted_at AS acceptedAt
                FROM agent_runs r JOIN conversations c ON c.tenant_id=r.tenant_id AND c.id=r.conversation_id
                WHERE r.tenant_id=? AND r.requested_by=? ORDER BY r.accepted_at DESC,r.id DESC LIMIT 100
                """,
                p.tenant(),
                p.user());
    }

    public List<Map<String, Object>> events(Principal p, UUID id, long after) {
        status(p, id);
        var rows =
                sql.rows(
                        """
                        SELECT seq,attempt,event_type AS type,payload,created_at AS createdAt FROM run_events
                        WHERE tenant_id=? AND run_id=? AND seq>? ORDER BY seq LIMIT 128
                        """,
                        p.tenant(),
                        id,
                        after);
        for (var row : rows) {
            Sql.utcTimes(row, "createdAt");
            row.put("payload", sql.parseObject(row.get("payload")));
            row.put("schemaVersion", 1);
            row.put("runId", id);
            row.put("tenantId", p.tenant());
            row.put("eventId", id + ":" + row.get("seq"));
        }
        return rows;
    }

    /** Conversation prefix at a committed event boundary, for message and state snapshots. */
    public List<Map<String, Object>> historyThrough(Principal p, UUID run, long seq) {
        status(p, run);
        return historyRows(
                """
                FROM agent_runs target JOIN run_events boundary ON boundary.run_id=target.id AND boundary.seq=?
                JOIN agent_runs r ON r.conversation_id=target.conversation_id AND r.tenant_id=target.tenant_id
                       AND r.requested_by=target.requested_by AND (r.accepted_at,r.id)<=(target.accepted_at,target.id)
                JOIN run_events e ON e.run_id=r.id AND e.created_at<=boundary.created_at
                       AND (e.run_id<>target.id OR e.seq<=boundary.seq)
                WHERE target.tenant_id=? AND target.requested_by=? AND target.id=?
                """,
                seq,
                p.tenant(),
                p.user(),
                run);
    }

    public List<Map<String, Object>> threadHistory(Principal p, UUID thread) {
        return historyRows(
                """
                FROM agent_runs r JOIN run_events e ON e.run_id=r.id
                WHERE r.tenant_id=? AND r.requested_by=? AND r.conversation_id=?
                AND NOT (e.event_type='text.delta' AND r.status='SUCCEEDED' AND e.attempt=r.attempt)
                """,
                p.tenant(),
                p.user(),
                thread);
    }

    private List<Map<String, Object>> historyRows(String scope, Object... params) {
        var rows =
                sql.rows(
                        """
                        SELECT BIN_TO_UUID(r.id) runId, e.seq, e.attempt, e.event_type type, e.payload,
                            IF(e.event_type='run.accepted',r.input_json->>'$.text',NULL) input,
                            IF(e.event_type='run.accepted',r.input_json->>'$.messageId',NULL) messageId
                        """
                                + scope
                                + """
                                AND e.event_type IN ('run.accepted','run.claimed','run.recovery_required','text.delta',
                                    'plan.updated','tool.started','approval.required','tool.completed','run.completed',
                                    'run.cancelled','run.failed')
                                ORDER BY r.accepted_at,r.id,e.seq
                                """,
                        params);
        rows.forEach(row -> row.put("payload", sql.parseObject(row.get("payload"))));
        return rows;
    }

    public Map<String, Object> trace(Principal p, UUID id) {
        var run = status(p, id);
        var calls =
                sql.rows(
                        """
                        SELECT BIN_TO_UUID(id) AS callId,attempt,model,reserved,input_tokens AS inputTokens,
                          output_tokens AS outputTokens,currency,
                          ROUND((input_tokens*input_per_million+output_tokens*output_per_million)/1000000,9) AS cost,created_at AS startedAt FROM model_usage
                        WHERE tenant_id=? AND run_id=? ORDER BY created_at,id
                        """,
                        p.tenant(),
                        id);
        var boundaries =
                sql.rows(
                        """
                        SELECT seq,attempt,event_type AS type,created_at AS createdAt FROM run_events
                        WHERE tenant_id=? AND run_id=? AND event_type<>'text.delta' ORDER BY seq
                        """,
                        p.tenant(),
                        id);
        var timing =
                sql.first(
                                """
                                SELECT SUBSTRING(traceparent,4,32) AS traceId,
                                  TIMESTAMPDIFF(MICROSECOND,accepted_at,started_at) DIV 1000 AS queueMs,
                                  TIMESTAMPDIFF(MICROSECOND,started_at,finished_at) DIV 1000 AS elapsedMs
                                FROM agent_runs WHERE tenant_id=? AND id=?
                                """,
                                p.tenant(),
                                id)
                        .orElseThrow();
        return Map.of("run", run, "timing", timing, "modelCalls", calls, "events", boundaries);
    }
}
