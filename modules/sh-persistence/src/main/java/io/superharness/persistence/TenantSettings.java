package io.superharness.persistence;

import io.superharness.domain.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class TenantSettings {
    private final Sql sql;
    private final Catalog catalog;

    public TenantSettings(Sql sql, Catalog catalog) {
        this.sql = sql;
        this.catalog = catalog;
    }

    private void owner(Principal p) {
        if (!catalog.role(p).equals("OWNER")) throw Problem.denied();
    }

    public Map<String, Object> settings(Principal p) {
        String role = catalog.role(p);
        var result =
                sql.first(
                                """
                                SELECT id,max_open_runs AS maxOpenRuns,daily_token_budget AS dailyTokens,
                                  run_token_budget AS runTokens,run_tool_limit AS runTools FROM tenants WHERE id=?
                                """,
                                p.tenant())
                        .orElseThrow(Problem::missing);
        result.put("role", role);
        return result;
    }

    public List<Map<String, Object>> members(Principal p) {
        owner(p);
        return sql.rows(
                "SELECT user_id AS subject,role,active FROM memberships WHERE tenant_id=? ORDER BY"
                    + " user_id",
                p.tenant());
    }

    public List<Map<String, Object>> audit(Principal p) {
        owner(p);
        return sql.rows(
                """
                SELECT id,actor_id AS actor,action,BIN_TO_UUID(resource_id) AS resource,details,created_at AS createdAt
                FROM audit_records WHERE tenant_id=? ORDER BY id DESC LIMIT 100
                """,
                p.tenant());
    }

    private void audit(Principal p, String action, Object details) {
        sql.update(
                "INSERT INTO audit_records(tenant_id,actor_id,action,resource_id,details)"
                    + " VALUES(?,?,?,?,?)",
                p.tenant(),
                p.user(),
                action,
                UUID.randomUUID(),
                sql.json(details));
    }

    @Transactional
    public void member(Principal p, String subject, String role, boolean active) {
        // Serialize owner changes with each other so the last owner can always administer the
        // tenant.
        sql.first("SELECT id FROM tenants WHERE id=? FOR UPDATE", p.tenant())
                .orElseThrow(Problem::missing);
        owner(p);
        if (p.user().equals(subject)
                && (!active || !"OWNER".equals(role))
                && sql.count(
                                "SELECT COUNT(*) FROM memberships WHERE tenant_id=? AND"
                                    + " role='OWNER' AND active=TRUE",
                                p.tenant())
                        == 1) throw new Problem(409, "LAST_OWNER");
        sql.update(
                """
                INSERT INTO memberships(tenant_id,user_id,role,active) VALUES(?,?,?,?)
                ON DUPLICATE KEY UPDATE role=?,active=?
                """,
                p.tenant(),
                subject,
                role,
                active,
                role,
                active);
        audit(p, "MEMBER_UPDATED", Map.of("subject", subject, "role", role, "active", active));
    }

    @Transactional
    public void limits(Principal p, int maxOpen, long daily, int perRun, int tools) {
        owner(p);
        sql.update(
                "UPDATE tenants SET"
                    + " max_open_runs=?,daily_token_budget=?,run_token_budget=?,run_tool_limit=?"
                    + " WHERE id=?",
                maxOpen,
                daily,
                perRun,
                tools,
                p.tenant());
        audit(
                p,
                "QUOTA_UPDATED",
                Map.of(
                        "maxOpenRuns",
                        maxOpen,
                        "dailyTokens",
                        daily,
                        "runTokens",
                        perRun,
                        "runTools",
                        tools));
    }
}
