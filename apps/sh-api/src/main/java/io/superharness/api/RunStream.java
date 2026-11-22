package io.superharness.api;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.superharness.domain.*;
import io.superharness.runengine.*;

import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.*;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/runs/{id}")
class RunStream {
    private final Runs runs;
    private final RunLifecycle lifecycle;
    private final ObjectMapper mapper;

    RunStream(Runs runs, RunLifecycle lifecycle, ObjectMapper mapper) {
        this.runs = runs;
        this.lifecycle = lifecycle;
        this.mapper = mapper;
    }

    @PostMapping("/cancel")
    Object cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        var p = Identity.principal(jwt);
        lifecycle.cancel(p, id);
        return runs.status(p, id);
    }

    @PostMapping("/resume")
    Object resume(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        var p = Identity.principal(jwt);
        lifecycle.resume(p, id);
        return runs.status(p, id);
    }

    @io.swagger.v3.oas.annotations.Operation(
            summary = "Replay committed run events in sequence order",
            description =
                    "SSE id is runId:seq. Last-Event-ID takes precedence over afterSeq."
                            + " Disconnecting leaves the run active.")
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    ResponseEntity<StreamingResponseBody> events(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestHeader(value = "Last-Event-ID", required = false) String header,
            @RequestParam(defaultValue = "0") long afterSeq,
            jakarta.servlet.http.HttpServletRequest request,
            jakarta.servlet.http.HttpServletResponse response) {
        Principal p = Identity.principal(jwt);
        var status = runs.status(p, id);
        long start = cursor(id, header, afterSeq);
        if (start < 0 || start > ((Number) status.get("lastSeq")).longValue())
            throw new Problem(400, "INVALID_EVENT_CURSOR");
        return response(
                request,
                out ->
                        tail(
                                runs,
                                p,
                                id,
                                start,
                                jwt,
                                out,
                                response,
                                event -> {
                                    out.write(
                                            ("id: "
                                                            + event.get("eventId")
                                                            + "\nevent: "
                                                            + event.get("type")
                                                            + "\ndata: ")
                                                    .getBytes(UTF_8));
                                    out.write(mapper.writeValueAsBytes(event));
                                    out.write("\n\n".getBytes(UTF_8));
                                    return false;
                                }));
    }

    @FunctionalInterface
    interface EventWriter {
        /** True at a protocol terminal boundary, which can precede the platform terminal state. */
        boolean write(Map<String, Object> event) throws IOException;
    }

    static void tail(
            Runs runs,
            Principal p,
            UUID id,
            long after,
            Jwt jwt,
            OutputStream out,
            jakarta.servlet.http.HttpServletResponse response,
            EventWriter writer)
            throws IOException {
        long keepAlive = 0;
        while (jwt.getExpiresAt() != null && Instant.now().isBefore(jwt.getExpiresAt())) {
            var batch = runs.events(p, id, after);
            for (var event : batch) {
                if (writer.write(event)) {
                    out.flush();
                    return;
                }
                after = ((Number) event.get("seq")).longValue();
            }
            if (!batch.isEmpty()) {
                out.flush();
                response.flushBuffer();
                continue;
            }
            var current = runs.status(p, id);
            if (RunLifecycle.terminalStatus(current.get("status"))
                    && after >= ((Number) current.get("lastSeq")).longValue()) return;
            if (System.nanoTime() >= keepAlive) {
                out.write(": heartbeat\n\n".getBytes(UTF_8));
                out.flush();
                keepAlive = System.nanoTime() + 15_000_000_000L;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException stop) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    static ResponseEntity<StreamingResponseBody> response(
            jakarta.servlet.http.HttpServletRequest request, StreamingResponseBody stream) {
        org.springframework.web.filter.ShallowEtagHeaderFilter.disableContentCaching(request);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .cacheControl(CacheControl.noStore())
                .header("X-Accel-Buffering", "no")
                .body(stream);
    }

    private long cursor(UUID id, String header, long fallback) {
        if (header == null || header.isBlank()) return fallback;
        if (!header.startsWith(id + ":")) throw new Problem(400, "INVALID_EVENT_CURSOR");
        try {
            return Long.parseLong(header.substring(37));
        } catch (NumberFormatException e) {
            throw new Problem(400, "INVALID_EVENT_CURSOR");
        }
    }
}
