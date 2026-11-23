package io.superharness.api;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.superharness.domain.Principal;
import io.superharness.domain.Problem;
import io.superharness.persistence.*;
import io.superharness.runengine.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.io.IOException;
import java.util.*;

@Service
class AguiThreads {
    private final Runs runs;
    private final Approvals approvals;
    private final Catalog catalog;
    private final Sql sql;
    private final ObjectMapper mapper;

    AguiThreads(Runs runs, Approvals approvals, Catalog catalog, Sql sql, ObjectMapper mapper) {
        this.runs = runs;
        this.approvals = approvals;
        this.catalog = catalog;
        this.sql = sql;
        this.mapper = mapper;
    }

    record ThreadView(
            String cursor,
            List<Map<String, Object>> turns,
            List<Map<String, Object>> messages,
            Map<String, Object> state,
            List<Map<String, Object>> tools,
            List<Map<String, Object>> interrupts,
            boolean archived,
            String revisionId,
            Map<String, Object> capabilities) {}

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<ThreadView> snapshot(Principal p, UUID thread, String since)
            throws IOException {
        var role = catalog.role(p);
        var settings =
                sql.first(
                                """
                                SELECT CAST(c.archived AS UNSIGNED) archived, v.config->>'$.outputMode' outputMode,
                                       BIN_TO_UUID(c.revision_id) revisionId
                                FROM conversations c JOIN agent_revisions v ON c.revision_id=v.id AND c.tenant_id=v.tenant_id
                                WHERE c.tenant_id=? AND c.owner_id=? AND c.id=?
                                """,
                                p.tenant(),
                                p.user(),
                                thread)
                        .orElseThrow(Problem::missing);
        // Each committed transition appends an event. Polls for an unchanged thread need no replay.
        var version =
                sql.count(
                        """
                        SELECT COALESCE(SUM(next_seq),0) FROM agent_runs
                        WHERE tenant_id=? AND requested_by=? AND conversation_id=?
                        """,
                        p.tenant(),
                        p.user(),
                        thread);
        String cursor = sql.digest(List.of(thread, settings, role, version));
        if (cursor.equals(since)) return Optional.empty();

        var turns =
                sql.rows(
                        """
                        SELECT BIN_TO_UUID(id) runId, status, attempt, accepted_at createdAt, updated_at updatedAt,
                               input_json->>'$.text' input, COALESCE(input_json->>'$.messageId', CONCAT(BIN_TO_UUID(id),'-input')) messageId,
                               input_json->>'$.interactionId' interactionId, error_code errorCode
                        FROM agent_runs WHERE tenant_id=? AND requested_by=? AND conversation_id=? ORDER BY accepted_at,id
                        """,
                        p.tenant(),
                        p.user(),
                        thread);
        turns.forEach(row -> Sql.utcTimes(row, "createdAt", "updatedAt"));
        var view =
                AguiHistory.read(
                        runs.threadHistory(p, thread),
                        id -> approvals.proposal(p, thread, id),
                        mapper);
        Map<String, Object> capabilities =
                Map.of(
                        "tools",
                        Map.of("clientProvided", false),
                        "state",
                        Map.of("snapshots", true),
                        "humanInTheLoop",
                        Map.of(
                                "interrupts",
                                "APPROVED_ARTIFACT".equals(settings.get("outputMode"))),
                        "inputContentTypes",
                        List.of("text/plain"));
        return Optional.of(
                new ThreadView(
                        cursor,
                        turns,
                        view.messages(),
                        Map.of("tasks", view.tasks()),
                        view.tools(),
                        view.interrupt() == null ? List.of() : List.of(view.interrupt()),
                        ((Number) settings.get("archived")).intValue() != 0,
                        (String) settings.get("revisionId"),
                        capabilities));
    }
}
