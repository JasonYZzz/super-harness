package io.superharness.runengine;

import io.superharness.domain.*;
import io.superharness.persistence.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Durable continuation for the versioned publish_text tool. Network I/O is finished before
 * requesting approval.
 */
@Service
public class Approvals {
    private final Sql sql;
    private final Catalog catalog;
    private final EventJournal events;

    public Approvals(Sql sql, Catalog catalog, EventJournal events) {
        this.sql = sql;
        this.catalog = catalog;
        this.events = events;
    }

    public List<Map<String, Object>> pending(Principal p) {
        var role = catalog.role(p);
        boolean reviewer = Set.of("OWNER", "APPROVER").contains(role);
        var pending =
                sql.rows(
                        """
                        SELECT BIN_TO_UUID(a.id) AS id,BIN_TO_UUID(a.run_id) AS runId,a.requested_args_sha256 AS argsHash,
                          a.status,a.expires_at AS expiresAt,t.tool_name AS tool,t.tool_version AS version,
                          LEFT(t.result_ref->>'$.text',1000) AS preview,t.result_ref->>'$.size' AS bytes
                        FROM approvals a JOIN tool_executions t ON t.tenant_id=a.tenant_id AND t.id=a.tool_execution_id
                        JOIN agent_runs r ON r.tenant_id=a.tenant_id AND r.id=a.run_id
                        WHERE a.tenant_id=? AND (? OR a.requested_by=?) AND a.status='PENDING' AND r.status='WAITING_APPROVAL'
                        ORDER BY a.expires_at LIMIT 100
                        """,
                        p.tenant(),
                        reviewer,
                        p.user());
        pending.forEach(row -> row.put("canDecide", reviewer));
        return pending;
    }

    @Transactional
    public void decide(Principal p, UUID id, String hash, boolean approve) {
        if (!Set.of("OWNER", "APPROVER").contains(catalog.role(p))) throw Problem.denied();
        var link =
                sql.first("SELECT run_id FROM approvals WHERE tenant_id=? AND id=?", p.tenant(), id)
                        .orElseThrow(Problem::missing);
        UUID run = Sql.uuid(link.get("run_id"));
        var r =
                sql.first(
                                "SELECT * FROM agent_runs WHERE tenant_id=? AND id=? FOR UPDATE",
                                p.tenant(),
                                run)
                        .orElseThrow(Problem::missing);
        var a =
                sql.first(
                                """
                                SELECT a.*,t.args_sha256,t.result_ref FROM approvals a JOIN tool_executions t
                                ON t.id=a.tool_execution_id AND t.tenant_id=a.tenant_id AND t.run_id=a.run_id WHERE a.tenant_id=? AND a.id=? FOR UPDATE
                                """,
                                p.tenant(),
                                id)
                        .orElseThrow();
        if (!hash.equals(a.get("requested_args_sha256")) || !hash.equals(a.get("args_sha256")))
            throw new Problem(409, "APPROVAL_HASH_MISMATCH");
        String decision = approve ? "APPROVED" : "REJECTED";
        if (decision.equals(a.get("status"))) return;
        if (!"WAITING_APPROVAL".equals(r.get("status"))
                || sql.update(
                                """
                                UPDATE approvals SET status=?,decided_by=?,decided_at=UTC_TIMESTAMP(6)
                                WHERE tenant_id=? AND id=? AND status='PENDING' AND expires_at>UTC_TIMESTAMP(6)
                                """,
                                decision,
                                p.user(),
                                p.tenant(),
                                id)
                        != 1) throw new Problem(409, "APPROVAL_CLOSED");
        events.append(
                p.tenant(),
                run,
                "approval.decided",
                Map.of("approvalId", id, "decision", decision, "actor", p.user()));
        var tool = Sql.uuid(a.get("tool_execution_id"));
        var args = sql.parse(a.get("result_ref"));
        if (!hash.equals(
                sql.digest(
                        Map.of(
                                "tool",
                                "publish_text",
                                "version",
                                "1",
                                "sha256",
                                args.path("sha256").asText()))))
            throw new IllegalStateException("Approval payload mismatch");
        if (approve) {
            // Tool identity and artifact receipt share an ID: replay is a no-op inside this
            // transaction.
            sql.update(
                    """
                    INSERT INTO artifacts(id,tenant_id,run_id,tool_execution_id,object_key,sha256,byte_length,file_name,media_type)
                    VALUES(?,?,?,?,?,?,?,'response.txt','text/plain')
                    """,
                    tool,
                    p.tenant(),
                    run,
                    tool,
                    args.get("key").asText(),
                    args.get("sha256").asText(),
                    args.get("size").asInt());
            sql.update(
                    "UPDATE conversations SET state_slot=? WHERE tenant_id=? AND id=?",
                    r.get("slot_id"),
                    p.tenant(),
                    r.get("conversation_id"));
            events.append(p.tenant(), run, "tool.completed", Map.of("receipt", tool));
            events.append(p.tenant(), run, "artifact.created", Map.of("artifactId", tool));
        }
        sql.update(
                "UPDATE tool_executions SET status=?,external_receipt=? WHERE tenant_id=? AND id=?",
                approve ? "SUCCEEDED" : "DENIED",
                approve ? tool.toString() : null,
                p.tenant(),
                tool);
        sql.update(
                "UPDATE agent_runs SET"
                        + " status=?,error_code=?,finished_at=UTC_TIMESTAMP(6),fence=fence+1 WHERE"
                        + " tenant_id=? AND id=?",
                approve ? "SUCCEEDED" : "FAILED",
                approve ? null : "APPROVAL_REJECTED",
                p.tenant(),
                run);
        sql.update(
                "DELETE FROM conversation_gates WHERE tenant_id=? AND run_id=?", p.tenant(), run);
        sql.update(
                "INSERT INTO audit_records(tenant_id,actor_id,action,resource_id,details)"
                        + " VALUES(?,?,?,?,?)",
                p.tenant(),
                p.user(),
                decision,
                id,
                sql.json(Map.of("argsHash", hash, "tool", tool)));
        events.append(
                p.tenant(),
                run,
                approve ? "run.completed" : "run.failed",
                approve
                        ? Map.of("text", args.get("text").asText())
                        : Map.of("code", "APPROVAL_REJECTED"));
    }
}
