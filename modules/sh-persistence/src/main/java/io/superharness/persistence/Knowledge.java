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
    public UUID publish(Principal p, String name, String version, String content) {
        catalog.requireEditor(p);
        var id = UUID.randomUUID();
        try {
            sql.update(
                    "INSERT INTO skill_versions(id,tenant_id,name,version,content,checksum,author)"
                            + " VALUES(?,?,?,?,?,?,?)",
                    id,
                    p.tenant(),
                    name,
                    version,
                    content,
                    sql.digest(List.of(name, version, content)),
                    p.user());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new Problem(409, "SKILL_VERSION_EXISTS");
        }
        return id;
    }

    public List<Map<String, Object>> skills(Principal p) {
        catalog.role(p);
        return sql.rows(
                "SELECT BIN_TO_UUID(id) AS id,name,version,checksum,revoked FROM skill_versions"
                        + " WHERE tenant_id=? ORDER BY name,version",
                p.tenant());
    }

    public Map<String, Object> skill(Principal p, UUID id) {
        catalog.role(p);
        return sql.first(
                        "SELECT BIN_TO_UUID(id) AS id,name,version,content,revoked FROM"
                            + " skill_versions WHERE tenant_id=? AND id=?",
                        p.tenant(),
                        id)
                .orElseThrow(Problem::missing);
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
        return ids.stream()
                .map(
                        id ->
                                sql.first(
                                                """
                                                SELECT BIN_TO_UUID(id) AS id,name,version,checksum,content FROM skill_versions
                                                WHERE tenant_id=? AND id=? AND revoked=FALSE
                                                """,
                                                tenant,
                                                id)
                                        .orElseThrow(Problem::missing))
                .toList();
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
