package io.superharness.api;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.superharness.domain.*;
import io.superharness.runengine.*;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.ApiResponse;

import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.*;

@RestController
class AguiApi {
    private final Runs runs;
    private final Approvals approvals;
    private final RunLifecycle lifecycle;
    private final ObjectMapper mapper;

    AguiApi(Runs runs, Approvals approvals, RunLifecycle lifecycle, ObjectMapper mapper) {
        this.runs = runs;
        this.approvals = approvals;
        this.lifecycle = lifecycle;
        this.mapper = mapper;
    }

    @io.swagger.v3.oas.annotations.Operation(
            summary = "Execute or replay a durable AG-UI interaction",
            description =
                    "Submit one user text message or resume a publish_text interrupt. Repeating the"
                            + " original input replays the complete interaction.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = AguiInput.class),
                            examples = {
                                @ExampleObject(
                                        name = "Text turn",
                                        value =
                                                """
                                                {"threadId":"11111111-1111-4111-8111-111111111111","runId":"turn-1",
                                                 "messages":[{"id":"input-1","role":"user","content":"整理发布步骤"}]}
                                                """),
                                @ExampleObject(
                                        name = "Approve publication",
                                        value =
                                                """
                                                {"threadId":"11111111-1111-4111-8111-111111111111","runId":"decision-1","messages":[],
                                                 "resume":[{"interruptId":"22222222-2222-4222-8222-222222222222","status":"resolved",
                                                   "payload":{"approved":true,"argsHash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}}]}
                                                """)
                            }))
    @ApiResponse(
            responseCode = "200",
            description = "AG-UI events through the first interrupt or terminal outcome",
            content = @Content(mediaType = "text/event-stream", schema = @Schema(type = "string")))
    @ApiResponse(
            responseCode = "400",
            description = "Invalid or unsupported input",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(type = "object"),
                            schemaProperties =
                                    @SchemaProperty(
                                            name = "code",
                                            schema = @Schema(type = "string"))))
    @ApiResponse(
            responseCode = "403",
            description = "Membership or approval permission required",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(type = "object"),
                            schemaProperties =
                                    @SchemaProperty(
                                            name = "code",
                                            schema = @Schema(type = "string"))))
    @ApiResponse(
            responseCode = "404",
            description = "Conversation unavailable",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(type = "object"),
                            schemaProperties =
                                    @SchemaProperty(
                                            name = "code",
                                            schema = @Schema(type = "string"))))
    @ApiResponse(
            responseCode = "409",
            description = "Conflicting idempotent submission",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(type = "object"),
                            schemaProperties =
                                    @SchemaProperty(
                                            name = "code",
                                            schema = @Schema(type = "string"))))
    @ApiResponse(
            responseCode = "429",
            description = "Admission quota exceeded",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(type = "object"),
                            schemaProperties =
                                    @SchemaProperty(
                                            name = "code",
                                            schema = @Schema(type = "string"))))
    @PostMapping(value = "/api/v1/ag-ui/runs", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    ResponseEntity<StreamingResponseBody> run(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody AguiInput input,
            jakarta.servlet.http.HttpServletRequest request,
            jakarta.servlet.http.HttpServletResponse response) {
        input.validate();
        var p = Identity.principal(jwt);
        UUID run;
        Map<String, Object> proposal = null;
        long cursor = 0;
        String failure = null;
        try {
            if (input.resume() != null && !input.resume().isEmpty()) {
                var decision = input.decision();
                proposal = approvals.proposal(p, input.threadId(), decision.approval());
                run = UUID.fromString(proposal.get("runId").toString());
                if (runs.interactionKey(input.threadId(), input.runId())
                        .equals(proposal.get("requestKey")))
                    throw new Problem(400, "RESUME_REQUIRES_NEW_RUN");
                if (decision.cancelled()) lifecycle.cancelApproval(p, run, decision.approval());
                else approvals.decide(p, decision.approval(), decision.hash(), decision.approved());
                cursor = ((Number) proposal.get("interruptSeq")).longValue();
            } else {
                var message = input.userMessage();
                var status =
                        runs.submitInteraction(
                                p, input.threadId(), input.runId(), message.id(), message.text());
                run = UUID.fromString(status.get("runId").toString());
            }
        } catch (Problem ex) {
            boolean resume = input.resume() != null && !input.resume().isEmpty();
            if (ex.status == 403 || (!resume && !ex.getMessage().equals("INTERRUPT_PENDING")))
                throw ex;
            failure = ex.status == 404 ? "INVALID_INTERRUPT" : ex.getMessage();
            run = null;
        }
        final var id = run;
        final var previous = proposal;
        final long start = cursor;
        final var error = failure;
        StreamingResponseBody body =
                out -> {
                    var writer =
                            new AguiProjection(
                                    input,
                                    id,
                                    previous,
                                    mapper,
                                    out,
                                    seq -> runs.historyThrough(p, id, seq),
                                    approval -> approvals.proposal(p, input.threadId(), approval));
                    if (error != null) {
                        writer.start();
                        writer.error(error);
                        return;
                    }
                    if (previous != null) writer.start();
                    RunStream.tail(runs, p, id, start, jwt, out, response, writer::accept);
                };
        return RunStream.response(request, body);
    }
}
