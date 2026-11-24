package io.superharness.api;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.superharness.contract.AguiInput;
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

    private final AguiThreads threads;

    AguiApi(
            Runs runs,
            Approvals approvals,
            RunLifecycle lifecycle,
            ObjectMapper mapper,
            AguiThreads threads) {
        this.threads = threads;
        this.runs = runs;
        this.approvals = approvals;
        this.lifecycle = lifecycle;
        this.mapper = mapper;
    }

    @io.swagger.v3.oas.annotations.Operation(
            summary = "Execute or replay a durable AG-UI interaction",
            description =
                    "Submit text, resume an interrupt, or use a Harness extension (harness.snapshot,"
                        + " harness.observe, harness.cancel, harness.recover, harness.enqueue, harness.withdraw). Repeating the"
                        + " original input replays the complete interaction.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = AguiInput.class),
                            examples = {
                                @ExampleObject(
                                        name = "Read thread",
                                        value =
                                                """
                                                {"threadId":"11111111-1111-4111-8111-111111111111","runId":"read-1",
                                                 "messages":[],"forwardedProps":{"harness.snapshot":{}}}
                                                """),
                                @ExampleObject(
                                        name = "Observe task",
                                        value =
                                                """
                                                {"threadId":"11111111-1111-4111-8111-111111111111","runId":"observe-1",
                                                 "messages":[],"forwardedProps":{"harness.observe":"33333333-3333-4333-8333-333333333333"}}
                                                """),
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
            jakarta.servlet.http.HttpServletResponse response)
            throws java.io.IOException {
        var extension = input.validate();
        var p = Identity.principal(jwt);
        if (extension instanceof AguiInput.Snapshot snapshot) {
            var view = threads.snapshot(p, input.threadId(), snapshot.since());
            return RunStream.response(
                    request, out -> projection(input, p, null, null, out).threadSnapshot(view));
        }
        if (extension instanceof AguiInput.TaskOperation operation) {
            var task = operation.task();
            if (!input.threadId().toString().equals(runs.status(p, task).get("conversationId")))
                throw Problem.missing();
            if (operation instanceof AguiInput.Cancel cancel) {
                lifecycle.cancel(p, cancel.task());
            } else if (operation instanceof AguiInput.Withdraw withdraw) {
                lifecycle.withdraw(p, withdraw.task());
            } else if (operation instanceof AguiInput.Recover recover) {
                lifecycle.resume(p, recover.task(), recover.attempt());
            } else {
                return RunStream.response(
                        request,
                        out ->
                                RunStream.tail(
                                        runs,
                                        p,
                                        task,
                                        0,
                                        jwt,
                                        out,
                                        response,
                                        projection(input, p, task, null, out)::acceptForReader));
            }
            return RunStream.response(
                    request, out -> projection(input, p, task, null, out).acknowledge(task));
        }
        boolean continuing = input.resume() != null && !input.resume().isEmpty();
        try {
            if (continuing) return RunStream.response(request, resume(input, p, jwt, response));
            var message = input.userMessage();
            var task =
                    runs.submitInteraction(
                            p, input.threadId(), input.runId(), message.id(), message.text());
            var id = UUID.fromString(task.get("runId").toString());
            if (extension instanceof AguiInput.Enqueue) return RunStream.response(request, out -> projection(input,p,id,null,out).acknowledge(id));
            return RunStream.response(
                    request,
                    out ->
                            RunStream.tail(
                                    runs,
                                    p,
                                    id,
                                    0,
                                    jwt,
                                    out,
                                    response,
                                    projection(input, p, id, null, out)::accept));
        } catch (Problem ex) {
            if (ex.status == 403 || (!continuing && !"INTERRUPT_PENDING".equals(ex.getMessage())))
                throw ex;
            String code = ex.status == 404 ? "INVALID_INTERRUPT" : ex.getMessage();
            return RunStream.response(
                    request,
                    out -> {
                        var writer = projection(input, p, null, null, out);
                        writer.start();
                        writer.error(code);
                    });
        }
    }

    private StreamingResponseBody resume(
            AguiInput input,
            Principal p,
            Jwt jwt,
            jakarta.servlet.http.HttpServletResponse response) {
        var decision = input.decision();
        var proposal = approvals.proposalForDecision(p, input.threadId(), decision.approval());
        var task = UUID.fromString(proposal.get("runId").toString());
        if (runs.interactionKey(input.threadId(), input.runId()).equals(proposal.get("requestKey")))
            throw new Problem(400, "RESUME_REQUIRES_NEW_RUN");
        boolean own = p.user().equals(proposal.get("requester"));
        if (decision.cancelled()) {
            if (!own) throw Problem.denied();
            lifecycle.cancelApproval(p, task, decision.approval());
        } else {
            var receipt =
                    approvals.decide(p, decision.approval(), decision.hash(), decision.approved());
            if (!own) {
                // The reviewer's grant covers the decision and its receipt only.
                return out ->
                        projection(input, p, null, null, out)
                                .reviewReceipt(receipt, decision.approved());
            }
        }
        long cursor = ((Number) proposal.get("interruptSeq")).longValue();
        return out -> {
            var writer = projection(input, p, task, proposal, out);
            writer.start();
            RunStream.tail(runs, p, task, cursor, jwt, out, response, writer::accept);
        };
    }

    private AguiProjection projection(
            AguiInput input,
            Principal p,
            UUID task,
            Map<String, Object> previous,
            java.io.OutputStream out) {
        return new AguiProjection(
                input,
                task,
                previous,
                mapper,
                out,
                seq -> runs.historyThrough(p, task, seq),
                approval -> approvals.proposal(p, input.threadId(), approval));
    }
}
