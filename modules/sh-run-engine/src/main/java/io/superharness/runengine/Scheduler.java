package io.superharness.runengine;

import io.superharness.persistence.*;
import io.superharness.runtimespi.AgentRuntime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.util.*;

@Service
public class Scheduler {
    private final Sql sql;
    private final EventJournal journal;

    public Scheduler(Sql sql, EventJournal journal) {
        this.sql = sql;
        this.journal = journal;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<Execution> claim(String owner, String profile, int version) {
        return claim(owner, profile, version, true);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<Execution> claim(
            String owner, String profile, int version, boolean supportsSandbox) {
        var candidate =
                sql.first(
                        """
SELECT r.id,r.tenant_id,r.conversation_id,r.attempt,r.fence,r.traceparent,r.input_json,r.requested_by,
  a.prompt,COALESCE(r.input_json->>'$.modelOverride.model',a.model) AS model,a.config,c.state_slot
FROM agent_runs r JOIN agent_revisions a ON a.tenant_id=r.tenant_id AND a.id=r.agent_revision_id
JOIN conversations c ON c.tenant_id=r.tenant_id AND c.id=r.conversation_id
WHERE r.status='QUEUED' AND r.cancel_requested=FALSE AND r.deadline_at>UTC_TIMESTAMP(6)
  AND a.store_profile=? AND a.store_version=?
  AND (? OR COALESCE(a.config->>'$.sandbox','false')<>'true')
  AND NOT EXISTS (SELECT 1 FROM conversation_gates g WHERE g.tenant_id=r.tenant_id
    AND g.conversation_id=r.conversation_id AND g.run_id<>r.id)
ORDER BY r.accepted_at,r.id LIMIT 1 FOR UPDATE OF r SKIP LOCKED
""",
                        profile,
                        version,
                        supportsSandbox);
        if (candidate.isEmpty()) return Optional.empty();
        var r = candidate.get();
        String tenant = (String) r.get("tenant_id");
        UUID id = Sql.uuid(r.get("id")),
                conv = Sql.uuid(r.get("conversation_id")),
                slot = UUID.randomUUID();
        sql.update(
                """
                INSERT INTO conversation_gates(tenant_id,conversation_id,run_id) VALUES(?,?,?)
                ON DUPLICATE KEY UPDATE run_id=conversation_gates.run_id
                """,
                tenant,
                conv,
                id);
        int admitted =
                sql.update(
                        """
UPDATE agent_runs r JOIN conversation_gates g ON g.tenant_id=r.tenant_id
  AND g.conversation_id=r.conversation_id AND g.run_id=r.id
SET r.status='CLAIMED',r.lease_owner=?,r.attempt=r.attempt+1,r.fence=r.fence+1,
  r.slot_id=?,r.lease_deadline=UTC_TIMESTAMP(6)+INTERVAL 60 SECOND,
  r.started_at=COALESCE(r.started_at,UTC_TIMESTAMP(6))
WHERE r.tenant_id=? AND r.id=? AND r.status='QUEUED'
""",
                        owner,
                        slot,
                        tenant,
                        id);
        if (admitted == 0) return Optional.empty();
        journal.append(tenant, id, "run.claimed", Map.of());
        var config = sql.parse(r.get("config"));
        StringBuilder prompt = new StringBuilder(r.get("prompt").toString());
        var skills = new ArrayList<AgentRuntime.PinnedSkill>();
        for (var skill : config.path("skills")) {
            skills.add(
                    new AgentRuntime.PinnedSkill(
                            UUID.fromString(skill.path("id").asText()),
                            skill.path("name").asText(),
                            skill.path("version").asText(),
                            skill.path("content").asText()));
            prompt.append("\n\n")
                    .append(skill.path("name").asText())
                    .append("\n")
                    .append(skill.path("content").asText());
        }
        String input = sql.parse(r.get("input_json")).path("text").asText();
        if (config.path("useMemory").asBoolean()) {
            var notes =
                    sql.rows(
                            "SELECT content FROM memory_entries WHERE tenant_id=? AND owner_id=?"
                                    + " ORDER BY created_at DESC,id LIMIT 4",
                            tenant,
                            r.get("requested_by"));
            if (!notes.isEmpty()) input = "用户保存的参考资料：" + sql.json(notes) + "\n当前请求：" + input;
        }
        var mcp =
                config.has("mcp")
                        ? new com.fasterxml.jackson.databind.ObjectMapper()
                                .convertValue(
                                        config.get("mcp"),
                                        io.superharness.runtimespi.McpProfile.class)
                        : null;
        return Optional.of(
                new Execution(
                        tenant,
                        id,
                        owner,
                        ((Number) r.get("fence")).longValue() + 1,
                        ((Number) r.get("attempt")).intValue() + 1,
                        (String) r.get("traceparent"),
                        profile,
                        version,
                        "APPROVED_ARTIFACT"
                                .equals(sql.parse(r.get("config")).path("outputMode").asText()),
                        new AgentRuntime.Turn(
                                conv,
                                slot,
                                Sql.uuid(r.get("state_slot")),
                                prompt.toString(),
                                (String) r.get("model"),
                                input,
                                config.path("sandbox").asBoolean(),
                                mcp,
                                sql.parse(r.get("input_json"))
                                        .path("permissionMode")
                                        .asText("default"),
                                skills,
                                config.path("useMemory").asBoolean())));
    }
}
