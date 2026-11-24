package io.superharness.persistence;

import io.superharness.domain.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class Catalog {
    private final Sql sql;

    public Catalog(Sql sql) {
        this.sql = sql;
    }

    public String role(Principal p) {
        return (String)
                sql.first(
                                "SELECT role FROM memberships WHERE tenant_id=? AND user_id=? AND"
                                        + " active=TRUE",
                                p.tenant(),
                                p.user())
                        .orElseThrow(Problem::denied)
                        .get("role");
    }

    public void requireEditor(Principal p) {
        if (!Set.of("OWNER", "EDITOR").contains(role(p))) throw Problem.denied();
    }

    @Transactional
    public UUID createAgent(Principal p, String name) {
        requireEditor(p);
        var id = UUID.randomUUID();
        sql.update(
                "INSERT INTO agent_definitions(id,tenant_id,owner_id,name) VALUES(?,?,?,?)",
                id,
                p.tenant(),
                p.user(),
                name);
        return id;
    }

    public List<Map<String, Object>> agents(Principal p) {
        var role = role(p);
        return sql.rows(
                """
                SELECT BIN_TO_UUID(a.id) AS id,a.name,a.owner_id AS ownerId,
                (?='OWNER' OR (?='EDITOR' AND a.owner_id=?)) AS editable,v.model,v.prompt,v.config
                FROM agent_definitions a LEFT JOIN agent_revisions v ON v.tenant_id=a.tenant_id AND v.agent_id=a.id
                AND v.revision=(SELECT MAX(revision) FROM agent_revisions WHERE tenant_id=a.tenant_id AND agent_id=a.id)
                WHERE a.tenant_id=? AND a.archived=FALSE ORDER BY a.name,a.id
                """,
                role,
                role,
                p.user(),
                p.tenant());
    }

    public List<Map<String, Object>> conversations(Principal p, UUID agent) {
        role(p);
        var rows =
                sql.rows(
                        """
                        SELECT BIN_TO_UUID(c.id) AS id,BIN_TO_UUID(c.agent_id) AS agentId,BIN_TO_UUID(c.revision_id) AS revisionId,
                         BIN_TO_UUID(c.schedule_id) AS scheduleId,t.source AS channelSource,t.thread_key AS channelThread,
                         c.owner_id AS ownerId,COALESCE((SELECT LEAST(c.created_at,MIN(r.accepted_at)) FROM agent_runs r
                           WHERE r.tenant_id=c.tenant_id AND r.conversation_id=c.id),c.created_at) AS createdAt,
                         COALESCE(c.model_override->>'$.model',v.model) AS model,c.permission_mode AS permissionMode,
                         (v.config->>'$.outputMode'='APPROVED_ARTIFACT') AS publishApproval,
                         COALESCE(c.display_name,(SELECT LEFT(r.input_json->>'$.text',80) FROM agent_runs r WHERE
                           r.tenant_id=c.tenant_id AND r.conversation_id=c.id ORDER BY r.accepted_at,r.id LIMIT 1)) AS name,
                         (SELECT r.status FROM agent_runs r WHERE r.tenant_id=c.tenant_id AND r.conversation_id=c.id
                           ORDER BY r.accepted_at DESC,r.id DESC LIMIT 1) AS status
                        FROM conversations c JOIN agent_revisions v ON v.tenant_id=c.tenant_id AND v.id=c.revision_id
                        LEFT JOIN channel_threads t ON t.tenant_id=c.tenant_id AND t.conversation_id=c.id
                        WHERE c.tenant_id=? AND c.owner_id=? AND c.agent_id=? AND c.archived=FALSE ORDER BY createdAt DESC,c.id
                        """,
                        p.tenant(),
                        p.user(),
                        agent);
        rows.forEach(row -> Sql.utcTimes(row, "createdAt"));
        return rows;
    }

    public Map<String,Object> conversationResources(Principal p, UUID id) {
        role(p);
        var row=sql.first("""
            SELECT r.revision,r.config,BIN_TO_UUID(c.library_id) AS libraryId FROM conversations c JOIN agent_revisions r
            ON r.tenant_id=c.tenant_id AND r.id=c.revision_id
            WHERE c.tenant_id=? AND c.owner_id=? AND c.id=?
            """,p.tenant(),p.user(),id).orElseThrow(Problem::missing);
        var config=sql.parse(row.remove("config"));
        var skills=new ArrayList<Map<String,String>>();
        for(var skill:config.path("skills")) {
            skills.add(Map.of("name",skill.path("name").asText(),"version",skill.path("version").asText(),
                "description",skill.path("description").asText()));
        }
        row.put("skills",skills);
        var mcp=config.path("mcp");
        if(mcp.isObject()) row.put("mcp", Map.of("id",mcp.path("id").asText(),"version",mcp.path("version").asText(),
            "tools",mcp.path("readOnlyTools")));
        row.put("useMemory",config.path("useMemory").asBoolean());
        return row;
    }

    @Transactional
    public void updateConversation(Principal p, UUID id, String name, boolean archive) {
        changeConversation(p, id, name, archive, null, null);
    }

    @Transactional
    public void changeConversation(
            Principal p,
            UUID id,
            String name,
            boolean archive,
            Map<String, Object> model,
            String permissionMode) {
        role(p);
        if (archive || model != null || permissionMode != null) {
            sql.count("SELECT max_open_runs FROM tenants WHERE id=? FOR UPDATE", p.tenant());
            if (sql.count(
                            "SELECT COUNT(*) FROM agent_runs WHERE tenant_id=? AND"
                                    + " conversation_id=? AND requested_by=? AND status NOT IN"
                                    + " ('FAILED','CANCELLED','SUCCEEDED')",
                            p.tenant(),
                            id,
                            p.user())
                    > 0) throw new Problem(409, "CONVERSATION_ACTIVE");
        }
        if (sql.update(
                        "UPDATE conversations SET display_name=COALESCE(?,display_name),archived=?,"
                            + " model_override=COALESCE(?,model_override),"
                            + " permission_mode=COALESCE(?,permission_mode) WHERE tenant_id=? AND"
                            + " owner_id=? AND id=? AND archived=FALSE",
                        name,
                        archive,
                        model == null ? null : sql.json(model),
                        permissionMode,
                        p.tenant(),
                        p.user(),
                        id)
                == 0) throw Problem.missing();
    }

    public Map<String, Object> agent(Principal p, UUID id) {
        role(p);
        return sql.first(
                        "SELECT BIN_TO_UUID(id) AS id,name,owner_id AS ownerId,archived FROM"
                                + " agent_definitions WHERE tenant_id=? AND id=?",
                        p.tenant(),
                        id)
                .orElseThrow(Problem::missing);
    }

    private void lockOwned(Principal p, UUID id) {
        requireEditor(p);
        var row =
                sql.first(
                                "SELECT owner_id FROM agent_definitions WHERE tenant_id=? AND id=?"
                                        + " AND archived=FALSE FOR UPDATE",
                                p.tenant(),
                                id)
                        .orElseThrow(Problem::missing);
        if (!p.user().equals(row.get("owner_id")) && !role(p).equals("OWNER"))
            throw Problem.denied();
    }

    @Transactional
    public void edit(Principal p, UUID id, String name, boolean archive) {
        lockOwned(p, id);
        sql.update(
                "UPDATE agent_definitions SET name=COALESCE(?,name),archived=? WHERE tenant_id=?"
                        + " AND id=?",
                name,
                archive,
                p.tenant(),
                id);
    }

    @Transactional
    public UUID publish(
            Principal p,
            UUID agent,
            String prompt,
            String model,
            String profile,
            int version,
            Map<String, Object> config) {
        lockOwned(p, agent);
        var id = UUID.randomUUID();
        long revision =
                sql.count(
                        "SELECT COALESCE(MAX(revision),0)+1 FROM agent_revisions WHERE tenant_id=?"
                                + " AND agent_id=?",
                        p.tenant(),
                        agent);
        var checksum = sql.digest(List.of(prompt, model, profile, version, config));
        sql.update(
                """
                INSERT INTO agent_revisions(id,tenant_id,agent_id,revision,prompt,model,store_profile,store_version,checksum,config)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                """,
                id,
                p.tenant(),
                agent,
                revision,
                prompt,
                model,
                profile,
                version,
                checksum,
                sql.json(config));
        return id;
    }

    public List<Map<String, Object>> revisions(Principal p, UUID agent) {
        agent(p, agent);
        return sql.rows(
                """
                SELECT BIN_TO_UUID(id) AS id,revision,prompt,model,store_profile AS storeProfile,
                  store_version AS storeVersion,checksum,config FROM agent_revisions
                WHERE tenant_id=? AND agent_id=? ORDER BY revision DESC
                """,
                p.tenant(),
                agent);
    }

    @Transactional
    public UUID conversation(Principal p, UUID agent, UUID revision) {
        return conversation(p, agent, revision, null);
    }

    @Transactional
    public UUID conversation(Principal p, UUID agent, UUID revision, Map<String, Object> model) {
        role(p);
        var id = UUID.randomUUID();
        int n =
                sql.update(
                        """
                        INSERT INTO conversations(id,tenant_id,owner_id,agent_id,revision_id,model_override)
                        SELECT ?,r.tenant_id,?,r.agent_id,r.id,? FROM agent_revisions r JOIN agent_definitions a
                        ON a.tenant_id=r.tenant_id AND a.id=r.agent_id WHERE r.tenant_id=? AND r.agent_id=? AND r.id=? AND a.archived=FALSE
                        """,
                        id,
                        p.user(),
                        model == null ? null : sql.json(model),
                        p.tenant(),
                        agent,
                        revision);
        if (n != 1) throw Problem.missing();
        return id;
    }
}
