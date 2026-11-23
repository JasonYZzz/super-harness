package io.superharness.api;

import io.superharness.domain.Problem;
import io.superharness.persistence.*;
import io.superharness.runengine.Runs;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/channels/http")
class ChannelApi {
    private final Sql sql;
    private final Catalog catalog;
    private final Runs runs;

    ChannelApi(Sql sql, Catalog catalog, Runs runs) {
        this.sql = sql;
        this.catalog = catalog;
        this.runs = runs;
    }

    record Inbound(
            @NotNull UUID agentId,
            @NotBlank @Size(max = 64) String source,
            @NotBlank @Size(max = 128) String threadId,
            @NotBlank @Size(max = 128) String messageId,
            @NotBlank @Size(max = 32000) String text) {}

    @GetMapping("/threads")
    Object threads(@AuthenticationPrincipal Jwt jwt) {
        var p = Identity.principal(jwt);
        catalog.role(p);
        return sql.rows(
                """
                SELECT t.source,t.thread_key AS threadId,BIN_TO_UUID(t.agent_id) AS agentId,
                       BIN_TO_UUID(t.conversation_id) AS conversationId
                FROM channel_threads t JOIN conversations c ON c.id=t.conversation_id
                WHERE t.tenant_id=? AND t.owner_id=? AND c.archived=FALSE ORDER BY c.created_at DESC
                """,
                p.tenant(),
                p.user());
    }

    @Transactional
    @PostMapping("/messages")
    ResponseEntity<?> receive(
            @AuthenticationPrincipal Jwt jwt, @RequestBody @Valid Inbound message) {
        var p = Identity.principal(jwt);
        catalog.role(p);
        // Match run admission lock order so concurrent first deliveries create just one binding.
        sql.count("SELECT max_open_runs FROM tenants WHERE id=? FOR UPDATE", p.tenant());
        var existing =
                sql.first(
                        """
                        SELECT conversation_id FROM channel_threads WHERE tenant_id=? AND owner_id=?
                        AND agent_id=? AND source=? AND thread_key=?
                        """,
                        p.tenant(),
                        p.user(),
                        message.agentId(),
                        message.source(),
                        message.threadId());
        UUID conversation =
                existing.map(r -> Sql.uuid(r.get("conversation_id")))
                        .orElseGet(
                                () -> {
                                    var revisions = catalog.revisions(p, message.agentId());
                                    if (revisions.isEmpty())
                                        throw new Problem(409, "AGENT_REVISION_REQUIRED");
                                    UUID id =
                                            catalog.conversation(
                                                    p,
                                                    message.agentId(),
                                                    UUID.fromString(
                                                            (String)
                                                                    revisions
                                                                            .getFirst()
                                                                            .get("id")));
                                    sql.update(
                                            "INSERT INTO"
                                                + " channel_threads(tenant_id,owner_id,agent_id,source,thread_key,conversation_id)"
                                                + " VALUES(?,?,?,?,?,?)",
                                            p.tenant(),
                                            p.user(),
                                            message.agentId(),
                                            message.source(),
                                            message.threadId(),
                                            id);
                                    return id;
                                });
        String key = "channel-" + sql.digest(List.of(conversation, message.messageId()));
        var status =
                runs.submit(p, conversation, message.agentId(), null, message.text(), key, 1200);
        return ResponseEntity.accepted()
                .body(
                        Map.of(
                                "conversationId",
                                conversation,
                                "runId",
                                status.get("runId"),
                                "eventsUrl",
                                "/api/v1/runs/" + status.get("runId") + "/events"));
    }
}
