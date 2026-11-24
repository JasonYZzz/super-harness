package io.superharness.runengine;

import io.superharness.domain.*;
import io.superharness.persistence.*;

import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.*;

@Service
public class ScheduledTurns {
    private final Sql sql;
    private final Catalog catalog;
    private final Runs runs;
    private final TransactionTemplate tx;

    public ScheduledTurns(Sql sql, Catalog catalog, Runs runs, TransactionTemplate tx) {
        this.sql = sql;
        this.catalog = catalog;
        this.runs = runs;
        this.tx = tx;
    }

    public static Instant next(String expression, ZoneId zone, Instant after, Instant end) {
        if (expression.strip().split("\\s+").length != 5)
            throw new Problem(400, "INVALID_SCHEDULE_CRON");
        var occurrence = CronExpression.parse("0 " + expression).next(after.atZone(zone));
        if (occurrence == null || (end != null && occurrence.toInstant().isAfter(end))) return null;
        return occurrence.toInstant();
    }

    public void dispatchDue() {
        for (var row :
                sql.rows(
                        """
                        SELECT id,DATE_FORMAT(next_due,'%Y-%m-%d %H:%i:%s.%f') AS due_key FROM agent_schedules WHERE next_due<=UTC_TIMESTAMP(6)
                        AND (retry_after IS NULL OR retry_after<=UTC_TIMESTAMP(6)) ORDER BY next_due,id LIMIT 32
                        """)) {
            var id = Sql.uuid(row.get("id"));
            try {
                tx.executeWithoutResult(status -> dispatch(id, row.get("due_key")));
            } catch (Problem e) {
                // A quota or pending approval can clear. A revoked permission or skill needs
                // attention.
                boolean retry = e.status == 429 || e.getMessage().equals("INTERRUPT_PENDING");
                sql.update(
                        """
                        UPDATE agent_schedules SET last_error=?,retry_after=UTC_TIMESTAMP(6)+INTERVAL 1 MINUTE,
                        next_due=IF(?,next_due,NULL) WHERE id=? AND next_due=?
                        """,
                        e.getMessage(),
                        retry,
                        id,
                        row.get("due_key"));
            }
        }
    }

    private void dispatch(UUID id, Object due) {
        var found =
                sql.first(
                        """
                        SELECT s.*,UTC_TIMESTAMP(6) AS dispatch_at FROM agent_schedules s WHERE id=? AND next_due=? AND next_due<=UTC_TIMESTAMP(6) AND
                        (retry_after IS NULL OR retry_after<=UTC_TIMESTAMP(6)) FOR UPDATE SKIP LOCKED
                        """,
                        id,
                        due);
        if (found.isEmpty()) return;
        var row = found.get();
        var p = new Principal(row.get("tenant_id").toString(), row.get("owner_id").toString());
        var agent = Sql.uuid(row.get("agent_id"));
        var rev = Sql.uuid(row.get("revision_id"));
        catalog.role(p);
        sql.count("SELECT max_open_runs FROM tenants WHERE id=? FOR UPDATE", p.tenant());
        if (sql.count(
                        "SELECT COUNT(*) FROM agent_definitions WHERE tenant_id=? AND id=? AND"
                                + " archived=FALSE",
                        p.tenant(),
                        agent)
                == 0) throw new Problem(409, "SCHEDULE_AGENT_ARCHIVED");
        var settings = sql.parse(row.get("settings"));
        var prior =
                settings.path("stateful").asBoolean()
                        ? sql.first(
                                """
                                SELECT id FROM conversations WHERE tenant_id=? AND owner_id=? AND schedule_id=?
                                AND archived=FALSE ORDER BY created_at DESC LIMIT 1
                                """,
                                p.tenant(),
                                p.user(),
                                id)
                        : Optional.<Map<String, Object>>empty();
        UUID conversation;
        if (prior.isPresent()) conversation = Sql.uuid(prior.get().get("id"));
        else {
            conversation =
                    catalog.conversation(p, agent, rev, sql.parseObject(row.get("model_override")));
            sql.update(
                    "UPDATE conversations SET schedule_id=?,display_name=?,permission_mode=? WHERE"
                            + " id=?",
                    id,
                    settings.path("name").asText(),
                    settings.path("permission_mode").asText(),
                    conversation);
        }
        String prompt = settings.path("description").asText();
        runs.submitScheduled(
                p,
                conversation,
                agent,
                rev,
                prompt.isBlank() ? settings.path("name").asText() : prompt,
                "schedule-" + id + "-" + due,
                settings.path("permission_mode").asText(),
                sql.parseObject(row.get("model_override")));
        var end = settings.path("ended_at");
        Instant next =
                next(
                        settings.path("cron_expression").asText(),
                        ZoneId.of(settings.path("timezone").asText()),
                        ((LocalDateTime) row.get("dispatch_at")).toInstant(ZoneOffset.UTC),
                        end.isTextual() ? Instant.parse(end.asText()) : null);
        sql.update(
                "UPDATE agent_schedules SET next_due=?,last_error=NULL,retry_after=NULL WHERE id=?",
                next == null ? null : LocalDateTime.ofInstant(next, ZoneOffset.UTC),
                id);
    }
}
