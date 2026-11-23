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
            if (!path.startsWith("references/") || !files.add(path) || directories.contains(path))
                throw new Problem(400, "INVALID_SKILL_RESOURCE");
            String prefix = "";
            for (String segment : path.split("/", -1)) {
                if (segment.isBlank() || segment.startsWith(".") || segment.endsWith(".")
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
        catalog.role(p);
        return sql.rows(
                """
                SELECT BIN_TO_UUID(id) AS id,content,created_at AS createdAt FROM memory_entries
                WHERE tenant_id=? AND owner_id=? ORDER BY created_at DESC,id LIMIT 100
                """,
                p.tenant(),
                p.user());
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
