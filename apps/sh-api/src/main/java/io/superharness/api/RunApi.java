package io.superharness.api;

import io.superharness.contract.RunInput;
import io.superharness.runengine.Runs;

import jakarta.validation.Valid;

import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1")
class RunApi {
    private final Runs runs;

    RunApi(Runs runs) {
        this.runs = runs;
    }

    @io.swagger.v3.oas.annotations.Operation(
            summary = "Submit an idempotent run on a pinned conversation")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "202",
                description = "Accepted or previously accepted request",
                content =
                        @io.swagger.v3.oas.annotations.media.Content(
                                mediaType = "application/json",
                                schema =
                                        @io.swagger.v3.oas.annotations.media.Schema(
                                                implementation = Accepted.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description = "Conflicting key, revision or revoked skill"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "429",
                description = "Tenant admission quota exceeded")
    })
    @PostMapping("/runs")
    ResponseEntity<?> submit(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody @Valid RunInput body) {
        if (key.startsWith("agui-"))
            throw new io.superharness.domain.Problem(400, "RESERVED_IDEMPOTENCY_KEY");
        var status =
                runs.submit(
                        Identity.principal(jwt),
                        body.conversationId(),
                        body.agentId(),
                        body.agentRevisionId(),
                        body.input().text(),
                        key,
                        body.timeout(),
                        body.options() == null
                                || !Boolean.FALSE.equals(body.options().interactive()));
        var response = new LinkedHashMap<>(status);
        String url = "/api/v1/runs/" + status.get("runId");
        response.put("statusUrl", url);
        response.put("eventsUrl", url + "/events");
        return ResponseEntity.accepted().body(response);
    }

    record Accepted(
            UUID runId,
            UUID conversationId,
            UUID agentRevisionId,
            String status,
            int attempt,
            long lastSeq,
            boolean cancelRequested,
            String errorCode,
            java.time.LocalDateTime acceptedAt,
            String statusUrl,
            String eventsUrl) {}

    @GetMapping("/runs")
    Object list(@AuthenticationPrincipal Jwt jwt) {
        return runs.recent(Identity.principal(jwt));
    }

    @GetMapping("/runs/{id}")
    Object status(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return runs.status(Identity.principal(jwt), id);
    }

    @GetMapping("/runs/{id}/trace")
    Object trace(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return runs.trace(Identity.principal(jwt), id);
    }

    @GetMapping("/conversations/{id}/messages")
    Object messages(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return runs.messages(Identity.principal(jwt), id);
    }
}
