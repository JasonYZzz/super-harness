package io.superharness.persistence;

import io.superharness.domain.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class DocumentLibraries {
    private final Sql sql;
    private final Catalog catalog;

    public DocumentLibraries(Sql sql, Catalog catalog) {
        this.sql = sql;
        this.catalog = catalog;
    }

    public List<Map<String, Object>> list(Principal p) {
        catalog.role(p);
        return sql.rows(
                """
                SELECT BIN_TO_UUID(b.id) id,b.name,(SELECT COUNT(*) FROM library_documents WHERE library_id=b.id) documents
                FROM document_libraries b WHERE b.tenant_id=? AND b.owner_id=? ORDER BY b.created_at DESC,b.id
                """,
                p.tenant(),
                p.user());
    }

    public UUID create(Principal p, String name) {
        catalog.role(p);
        var id = UUID.randomUUID();
        sql.update(
                "INSERT INTO document_libraries(id,tenant_id,owner_id,name) VALUES(?,?,?,?)",
                id,
                p.tenant(),
                p.user(),
                name.strip());
        return id;
    }

    private void require(Principal p, UUID id) {
        catalog.role(p);
        if (sql.first(
                        "SELECT id FROM document_libraries WHERE tenant_id=? AND owner_id=? AND"
                            + " id=? FOR SHARE",
                        p.tenant(),
                        p.user(),
                        id)
                .isEmpty()) throw Problem.missing();
    }

    public void remove(Principal p, UUID id) {
        catalog.role(p);
        if (sql.update(
                        "DELETE FROM document_libraries WHERE tenant_id=? AND owner_id=? AND id=?",
                        p.tenant(),
                        p.user(),
                        id)
                == 0) throw Problem.missing();
    }

    @Transactional
    public UUID add(Principal p, UUID id, String name, String body) {
        require(p, id);
        var doc = UUID.randomUUID();
        sql.update(
                "INSERT INTO library_documents(id,library_id,name,body) VALUES(?,?,?,?)",
                doc,
                id,
                name.strip(),
                body);
        return doc;
    }

    @Transactional
    public void removeDoc(Principal p, UUID id, UUID doc) {
        require(p, id);
        if (sql.update("DELETE FROM library_documents WHERE library_id=? AND id=?", id, doc) == 0)
            throw Problem.missing();
    }

    @Transactional
    public List<Map<String, Object>> documents(Principal p, UUID id) {
        require(p, id);
        return sql.rows(
                "SELECT BIN_TO_UUID(id) id,name,CHAR_LENGTH(body) chars FROM library_documents"
                    + " WHERE library_id=? ORDER BY created_at DESC,id",
                id);
    }

    @Transactional
    public List<Map<String, Object>> search(Principal p, UUID id, String query) {
        require(p, id);
        if (query == null || query.isBlank() || query.length() > 256)
            throw new Problem(400, "INVALID_DOCUMENT_QUERY");
        return sql.rows(
                """
                SELECT BIN_TO_UUID(id) id,name,SUBSTRING(body,GREATEST(1,LOCATE(LOWER(?),LOWER(body))-120),800) excerpt
                FROM library_documents WHERE library_id=? AND LOCATE(LOWER(?),LOWER(body))>0
                ORDER BY created_at DESC,id LIMIT 10
                """,
                query,
                id,
                query);
    }

    @Transactional
    public void attach(Principal p, UUID conversation, UUID id) {
        catalog.role(p);
        sql.count("SELECT max_open_runs FROM tenants WHERE id=? FOR UPDATE", p.tenant());
        if (id != null) require(p, id);
        if (sql.update(
                        "UPDATE conversations SET library_id=? WHERE tenant_id=? AND owner_id=? AND"
                            + " id=? AND archived=FALSE",
                        id,
                        p.tenant(),
                        p.user(),
                        conversation)
                == 0) throw Problem.missing();
    }
}
