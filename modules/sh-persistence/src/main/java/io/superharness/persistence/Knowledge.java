package io.superharness.persistence;

import io.superharness.domain.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class Knowledge {
    private final Sql sql;
    private final Catalog catalog;

    public Knowledge(Sql sql, Catalog catalog) {
        this.sql = sql;
        this.catalog = catalog;
    }

    @Transactional
    public UUID publish(Principal p, String name, String version, String content,
            String description, Map<String, String> resources) {
        catalog.requireEditor(p);
        validateResources(resources);
        var id = UUID.randomUUID();
        try {
            sql.update(
                    "INSERT INTO skill_versions(id,tenant_id,name,version,content,description,resources,checksum,author)"
                            + " VALUES(?,?,?,?,?,?,?,?,?)",
                    id,
                    p.tenant(),
                    name,
                    version,
                    content,
                    description,
                    sql.json(resources),
                    sql.digest(List.of(name, version, description, content, resources)),
                    p.user());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new Problem(409, "SKILL_VERSION_EXISTS");
        }
        return id;
    }

    private static void validateResources(Map<String, String> resources) {
        var files = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        var directories = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        for (String key : resources.keySet()) {
            String path = java.text.Normalizer.normalize(key, java.text.Normalizer.Form.NFC);
            boolean supported = path.startsWith("references/") || path.startsWith("scripts/") || path.startsWith("assets/");
            if (!supported || !files.add(path) || directories.contains(path))
                throw new Problem(400, "INVALID_SKILL_RESOURCE");
            String prefix = "";
            for (String segment : path.split("/", -1)) {
                if (segment.isBlank() || segment.startsWith(".") || segment.endsWith(".")
                        || segment.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 255
                        || !segment.matches("[\\p{L}\\p{N}_ ()+.\\-]+"))
                    throw new Problem(400, "INVALID_SKILL_RESOURCE");
                if (!prefix.isEmpty()) {
                    if (files.contains(prefix)) throw new Problem(400, "INVALID_SKILL_RESOURCE");
                    directories.add(prefix);
                }
                prefix = prefix.isEmpty() ? segment : prefix + "/" + segment;
            }
        }
    }

    public List<Map<String, Object>> skills(Principal p) {
        catalog.role(p);
        return sql.rows(
                "SELECT BIN_TO_UUID(id) AS id,name,version,description,author,checksum,revoked FROM skill_versions"
                        + " WHERE tenant_id=? ORDER BY name,version",
                p.tenant());
    }

    public Map<String, Object> skill(Principal p, UUID id) {
        catalog.role(p);
        var row = sql.first(
                        "SELECT BIN_TO_UUID(id) AS id,name,version,content,description,author,revoked FROM"
                            + " skill_versions WHERE tenant_id=? AND id=?",
                        p.tenant(),
                        id)
                .orElseThrow(Problem::missing);
        row.put("usedBy", sql.rows("""
                SELECT a.name AS agent, r.revision FROM agent_revisions r
                JOIN agent_definitions a ON a.tenant_id=r.tenant_id AND a.id=r.agent_id
                WHERE r.tenant_id=? AND JSON_CONTAINS(r.config, JSON_OBJECT('id', ?), '$.skills')
                ORDER BY a.name, r.revision
                """, p.tenant(), id.toString()));
        return row;
    }

    @Transactional
    public void revoke(Principal p, UUID id) {
        catalog.requireEditor(p);
        if (sql.update(
                        "UPDATE skill_versions SET revoked=TRUE WHERE tenant_id=? AND id=?",
                        p.tenant(),
                        id)
                != 1) throw Problem.missing();
    }

    public List<Map<String, Object>> pin(String tenant, List<UUID> ids) {
        if (ids == null) return List.of();
        if (ids.size() > 4 || new HashSet<>(ids).size() != ids.size())
            throw new Problem(400, "INVALID_SKILL_LIST");
        var pinned = ids.stream()
                .map(
                        id ->
                                sql.first(
                                                """
                                                SELECT BIN_TO_UUID(id) AS id,name,version,description,resources,checksum,content FROM skill_versions
                                                WHERE tenant_id=? AND id=? AND revoked=FALSE
                                                """,
                                                tenant,
                                                id)
                                        .orElseThrow(Problem::missing))
                .toList();
        var names = new HashSet<String>();
        for (var row : pinned) {
            if (!names.add((String) row.get("name"))) throw new Problem(400, "DUPLICATE_SKILL_NAME");
            var resources = row.get("resources");
            row.put("resources", resources == null ? Map.of() : sql.parseObject(resources));
        }
        return pinned;
    }

    public List<Map<String, Object>> memories(Principal p) {
        return memories(p, "", 0);
    }

    public List<Map<String, Object>> memories(Principal p, String query, int offset) {
        catalog.role(p);
        return sql.rows(
                """
                SELECT BIN_TO_UUID(m.id) AS id,m.content,m.created_at AS createdAt,m.source,
                    a.name AS agentName,m.expires_at AS expiresAt FROM memory_entries m
                LEFT JOIN agent_definitions a ON a.tenant_id=m.tenant_id AND a.id=m.agent_id
                WHERE m.tenant_id=? AND m.owner_id=? AND LOCATE(LOWER(?),LOWER(m.content))>0
                    AND (m.expires_at IS NULL OR m.expires_at>UTC_TIMESTAMP(6))
                ORDER BY m.created_at DESC,m.id LIMIT 100 OFFSET ?
                """,
                p.tenant(),
                p.user(),
                query,
                offset);
    }

    public List<String> recall(
            String tenant, String owner, UUID conversation, String query, int limit) {
        return sql
                .rows(
                        """
                        SELECT content FROM memory_entries WHERE tenant_id=? AND owner_id=?
                            AND (source<>'LEGACY' OR conversation_id=?)
                            AND (agent_id IS NULL OR agent_id=(SELECT agent_id FROM conversations WHERE id=?))
                            AND (expires_at IS NULL OR expires_at>UTC_TIMESTAMP(6))
                            AND LOCATE(LOWER(?),LOWER(content))>0
                        ORDER BY created_at DESC,id LIMIT ?
                        """,
                        tenant,
                        owner,
                        conversation,
                        conversation,
                        query,
                        limit)
                .stream()
                .map(row -> row.get("content").toString())
                .toList();
    }

    public String legacyFile(String tenant, String owner, UUID conversation, String path) {
        return sql
                .rows(
                        """
                        SELECT content FROM memory_entries WHERE tenant_id=? AND owner_id=? AND conversation_id=?
                        AND source='LEGACY' AND legacy_path=? ORDER BY legacy_part
                        """,
                        tenant,
                        owner,
                        conversation,
                        path)
                .stream()
                .map(r -> r.get("content").toString())
                .collect(java.util.stream.Collectors.joining());
    }

    /** Called inside the transaction publishing the fenced execution slot. */
    public void publishMemory(Map<String, Object> run) {
        if (run.get("pending_memory") == null) return;
        var pending = sql.parse(run.get("pending_memory"));
        var tenant = run.get("tenant_id");
        var owner = run.get("requested_by");
        var conversation = run.get("conversation_id");
        for (var fact : pending.path("facts")) {
            sql.update(
                    """
                    INSERT INTO memory_entries(id,tenant_id,owner_id,content,source)
                    VALUES(?,?,?,?,'CHAT') ON DUPLICATE KEY UPDATE id=memory_entries.id
                    """,
                    UUID.randomUUID(),
                    tenant,
                    owner,
                    fact.asText());
        }
        // Imported once, as the new slot no longer carries the old memory_files state.
        int part = 0;
        for (var note : pending.path("legacy")) {
            String chunk = note.asText();
            int newline = chunk.indexOf('\n');
            sql.update(
                    """
                    INSERT INTO memory_entries(id,tenant_id,owner_id,content,source,agent_id,conversation_id,legacy_path,legacy_part)
                    SELECT ?,tenant_id,owner_id,?,'LEGACY',agent_id,id,?,?
                    FROM conversations WHERE tenant_id=? AND id=? AND owner_id=?
                    """,
                    UUID.randomUUID(),
                    chunk.substring(newline + 1),
                    chunk.substring(0, newline),
                    part++,
                    tenant,
                    conversation,
                    owner);
        }
        if (!pending.hasNonNull("summary")) return;
        String summary = pending.get("summary").asText();
        if (pending.hasNonNull("summaryId")) {
            // A user may have deleted this summary since the attempt was claimed.
            sql.update(
                    """
                    UPDATE memory_entries SET content=?,created_at=UTC_TIMESTAMP(6),
                        expires_at=UTC_TIMESTAMP(6)+INTERVAL 90 DAY
                    WHERE id=? AND tenant_id=? AND owner_id=? AND conversation_id=?
                    """,
                    summary,
                    UUID.fromString(pending.get("summaryId").asText()),
                    tenant,
                    owner,
                    conversation);
        } else {
            sql.update(
                    """
                    INSERT INTO memory_entries(id,tenant_id,owner_id,content,source,agent_id,conversation_id,expires_at)
                    SELECT ?,tenant_id,owner_id,?,'SUMMARY',agent_id,id,UTC_TIMESTAMP(6)+INTERVAL 90 DAY
                    FROM conversations WHERE tenant_id=? AND owner_id=? AND id=?
                    ON DUPLICATE KEY UPDATE content=?,created_at=UTC_TIMESTAMP(6),expires_at=UTC_TIMESTAMP(6)+INTERVAL 90 DAY
                    """,
                    UUID.randomUUID(),
                    summary,
                    tenant,
                    owner,
                    conversation,
                    summary);
        }
    }

    public void pruneExpiredMemories() {
        sql.update("DELETE FROM memory_entries WHERE expires_at<=UTC_TIMESTAMP(6) LIMIT 1000");
    }

    @Transactional
    public UUID remember(Principal p, String text) {
        catalog.role(p);
        var id = UUID.randomUUID();
        sql.update(
                "INSERT INTO memory_entries(id,tenant_id,owner_id,content) VALUES(?,?,?,?)",
                id,
                p.tenant(),
                p.user(),
                text);
        return id;
    }

    @Transactional
    public void forget(Principal p, UUID id) {
        catalog.role(p);
        if (sql.update(
                        "DELETE FROM memory_entries WHERE tenant_id=? AND owner_id=? AND id=?",
                        p.tenant(),
                        p.user(),
                        id)
                != 1) throw Problem.missing();
    }
}
