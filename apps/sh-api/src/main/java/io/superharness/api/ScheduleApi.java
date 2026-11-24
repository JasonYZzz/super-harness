package io.superharness.api;

import io.superharness.domain.*;
import io.superharness.persistence.*;
import io.superharness.runengine.ScheduledTurns;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/schedules")
class ScheduleApi {
    private final Catalog catalog;
    private final Sql sql;
    private final ModelPrices prices;

    ScheduleApi(Catalog catalog, Sql sql, ModelPrices prices) {
        this.catalog = catalog;
        this.sql = sql;
        this.prices = prices;
    }

    record Schedule(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 16000) String description,
            @NotNull UUID agent_id,
            @NotBlank String model,
            @NotBlank
                    @Pattern(
                            regexp =
                                    "[0-9]{1,2} [0-9]{1,2} (?:[0-9]{1,2}|\\*) (?:[0-9]{1,2}|\\*)"
                                            + " (?:[0-7]|\\*)")
                    String cron_expression,
            @NotBlank @Size(max = 80) String timezone,
            @NotNull Instant started_at,
            Instant ended_at,
            boolean stateful,
            @NotNull @Pattern(regexp = "default|accept_edits|explore|bypass|dont_ask|plan")
                    String permission_mode) {}

    @Transactional
    @PostMapping
    Object create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Schedule body) {
        var p = Identity.principal(jwt);
        catalog.role(p);
        var override = prices.settings(body.model());
        var revision =
                sql.first(
                                """
                                SELECT r.id FROM agent_revisions r JOIN agent_definitions a ON a.id=r.agent_id AND a.tenant_id=r.tenant_id
                                WHERE a.id=? AND a.tenant_id=? AND a.archived=FALSE ORDER BY r.revision DESC LIMIT 1 FOR SHARE
                                """,
                                body.agent_id(),
                                p.tenant())
                        .orElseThrow(Problem::missing);
        Instant next =
                nextOccurrence(
                        body.cron_expression(),
                        body.timezone(),
                        body.started_at(),
                        body.ended_at());
        var settings = new LinkedHashMap<String, Object>();
        settings.put("name", body.name().strip());
        settings.put("description", Objects.toString(body.description(), ""));
        settings.put("enabled", true);
        settings.put("source", "USER");
        settings.put("source_session_id", "");
        settings.put("timezone", body.timezone());
        settings.put("cron_expression", body.cron_expression());
        settings.put("started_at", body.started_at().toString());
        settings.put("ended_at", body.ended_at() == null ? null : body.ended_at().toString());
        settings.put(
                "chat_model_config",
                Map.of(
                        "type",
                        "java",
                        "model",
                        body.model(),
                        "model_class",
                        "",
                        "parameters",
                        Map.of(),
                        "credential_id",
                        ""));
        settings.put("stateful", body.stateful());
        settings.put("permission_mode", body.permission_mode());
        var id = UUID.randomUUID();
        sql.update(
                """
                INSERT INTO agent_schedules(id,tenant_id,owner_id,agent_id,revision_id,settings,model_override,next_due)
                VALUES(?,?,?,?,?,?,?,?)
                """,
                id,
                p.tenant(),
                p.user(),
                body.agent_id(),
                revision.get("id"),
                sql.json(settings),
                sql.json(override),
                LocalDateTime.ofInstant(next, ZoneOffset.UTC));
        return Map.of("schedule_id", id);
    }

    private static Instant nextOccurrence(
            String expression, String zone, Instant start, Instant end) {
        Instant next;
        try {
            Instant now = Instant.now();
            next =
                    ScheduledTurns.next(
                            expression,
                            ZoneId.of(zone),
                            start.isAfter(now) ? start.minusSeconds(1) : now,
                            end);
        } catch (DateTimeException | IllegalArgumentException invalid) {
            throw new Problem(400, "INVALID_SCHEDULE_TIME");
        }
        if (next == null) throw new Problem(400, "NO_FUTURE_OCCURRENCE");
        return next;
    }

    record Activation(@NotNull Boolean enabled) {}

    @Transactional
    @PatchMapping("/{id}")
    void activate(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @Valid @RequestBody Activation change) {
        var p = Identity.principal(jwt);
        catalog.role(p);
        var row =
                sql.first(
                                "SELECT settings,next_due FROM agent_schedules WHERE tenant_id=?"
                                    + " AND owner_id=? AND id=? FOR UPDATE",
                                p.tenant(),
                                p.user(),
                                id)
                        .orElseThrow(Problem::missing);
        if (change.enabled() == (row.get("next_due") != null)) return;
        var data = sql.parse(row.get("settings"));
        var end = data.get("ended_at");
        var next =
                change.enabled()
                        ? nextOccurrence(
                                data.path("cron_expression").asText(),
                                data.path("timezone").asText(),
                                Instant.parse(data.path("started_at").asText()),
                                end.isTextual() ? Instant.parse(end.asText()) : null)
                        : null;
        sql.update(
                "UPDATE agent_schedules SET next_due=?,retry_after=NULL,last_error=NULL WHERE id=?",
                next == null ? null : LocalDateTime.ofInstant(next, ZoneOffset.UTC),
                id);
    }

    @GetMapping
    Object list(@AuthenticationPrincipal Jwt jwt) {
        var p = Identity.principal(jwt);
        catalog.role(p);
        var rows =
                sql.rows(
                        """
                        SELECT BIN_TO_UUID(id) id,BIN_TO_UUID(agent_id) agent_id,owner_id user_id,settings,
                        next_due,last_error,created_at FROM agent_schedules WHERE tenant_id=? AND owner_id=? ORDER BY created_at DESC,id
                        """,
                        p.tenant(),
                        p.user());
        for (var r : rows) {
            Sql.utcTimes(r, "created_at", "next_due");
            var settings = sql.parseObject(r.remove("settings"));
            settings.put("enabled", r.get("next_due") != null);
            r.put("data", settings);
            r.put("updated_at", r.get("created_at"));
        }
        return Map.of("schedules", rows, "total", rows.size());
    }

    @GetMapping("/{id}/sessions")
    Object sessions(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        var p = Identity.principal(jwt);
        catalog.role(p);
        if (sql.count(
                        "SELECT COUNT(*) FROM agent_schedules WHERE id=? AND tenant_id=? AND"
                                + " owner_id=?",
                        id,
                        p.tenant(),
                        p.user())
                == 0) throw Problem.missing();
        var rows =
                sql.rows(
                        """
                        SELECT BIN_TO_UUID(c.id) id,c.created_at,(SELECT r.status FROM agent_runs r WHERE r.tenant_id=c.tenant_id
                        AND r.conversation_id=c.id ORDER BY r.accepted_at DESC,r.id DESC LIMIT 1) status
                        FROM conversations c WHERE c.schedule_id=? AND c.tenant_id=? AND c.owner_id=? AND c.archived=FALSE ORDER BY c.created_at DESC
                        """,
                        id,
                        p.tenant(),
                        p.user());
        rows.forEach(r -> Sql.utcTimes(r, "created_at"));
        return Map.of("sessions", rows, "total", rows.size());
    }

    @DeleteMapping("/{id}")
    void remove(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        var p = Identity.principal(jwt);
        catalog.role(p);
        if (sql.update(
                        "DELETE FROM agent_schedules WHERE id=? AND tenant_id=? AND owner_id=?",
                        id,
                        p.tenant(),
                        p.user())
                == 0) throw Problem.missing();
    }
}
