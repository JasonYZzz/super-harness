package io.superharness.api;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.time.Instant;
import java.util.*;
import java.util.function.*;

/**
 * One deterministic projection of committed ledger rows. A protocol run ends at its first
 * interrupt.
 */
final class AguiProjection {
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(AguiProjection.class);
    private final AguiInput input;
    private final UUID run;
    private final Map<String, Object> resumed;
    private final ObjectMapper mapper;
    private final OutputStream out;
    private final LongFunction<List<Map<String, Object>>> history;
    private final Function<UUID, Map<String, Object>> proposals;
    private final Set<Object> counted = new HashSet<>();
    private final StringBuilder draft = new StringBuilder();
    private long inputTokens, outputTokens, timestamp, sequence;
    private int attempt;
    private boolean open;
    private Object tasks;

    AguiProjection(
            AguiInput input,
            UUID run,
            Map<String, Object> resumed,
            ObjectMapper mapper,
            OutputStream out,
            LongFunction<List<Map<String, Object>>> history,
            Function<UUID, Map<String, Object>> proposals) {
        this.input = input;
        this.run = run;
        this.resumed = resumed;
        this.mapper = mapper;
        this.out = out;
        this.history = history;
        this.proposals = proposals;
    }

    private void emit(String type, Map<String, ?> fields) throws IOException {
        var frame = new LinkedHashMap<String, Object>();
        frame.put("type", type);
        if (timestamp != 0) frame.put("timestamp", timestamp);
        frame.putAll(fields);
        out.write("data: ".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        out.write(mapper.writeValueAsBytes(frame));
        out.write('\n');
        out.write('\n');
    }

    void start() throws IOException {
        emit("RUN_STARTED", Map.of("threadId", input.threadId(), "runId", input.runId()));
    }

    void error(String code) throws IOException {
        log.info(
                "AG-UI run error thread={} interaction={} task={} code={}",
                input.threadId(),
                input.runId().replaceAll("\\p{Cntrl}", "_"),
                run,
                code);
        closeText();
        emit("RUN_ERROR", Map.of("code", code, "message", code));
    }

    private void finish(Map<String, ?> outcome) throws IOException {
        closeText();
        emit(
                "RUN_FINISHED",
                Map.of("threadId", input.threadId(), "runId", input.runId(), "outcome", outcome));
    }

    private String messageId() {
        return run + "-" + attempt;
    }

    private void closeText() throws IOException {
        if (open) {
            emit("TEXT_MESSAGE_END", Map.of("messageId", messageId()));
            open = false;
        }
    }

    private void delta(String text) throws IOException {
        if (text.isEmpty()) return;
        if (!open) {
            emit("TEXT_MESSAGE_START", Map.of("messageId", messageId(), "role", "assistant"));
            open = true;
        }
        draft.append(text);
        emit("TEXT_MESSAGE_CONTENT", Map.of("messageId", messageId(), "delta", text));
    }

    private void custom(String name, Object value) throws IOException {
        emit("CUSTOM", Map.of("name", name, "value", value));
    }

    private static Map<String, Long> tokens(long in, long output) {
        return Map.of("inputTokens", in, "outputTokens", output, "totalTokens", in + output);
    }

    @SuppressWarnings("unchecked")
    boolean accept(Map<String, Object> row) throws IOException {
        String type = row.get("type").toString();
        sequence = ((Number) row.get("seq")).longValue();
        int next = ((Number) row.get("attempt")).intValue();
        timestamp = Instant.parse(row.get("createdAt").toString()).toEpochMilli();
        if (attempt != next) {
            closeText();
            attempt = next;
            draft.setLength(0);
        }
        var p = (Map<String, Object>) row.get("payload");
        switch (type) {
            case "run.accepted" -> {
                start();
                status(type);
            }
            case "run.claimed",
                    "run.started",
                    "run.recovering",
                    "run.recovery_required",
                    "run.cancel_requested" ->
                    status(type);
            case "text.delta" -> delta((String) p.get("text"));
            case "plan.updated" -> {
                tasks = p.get("tasks");
                emitState();
            }
            case "usage.updated" -> {
                if (counted.add(p.get("callId"))) {
                    long in = ((Number) p.get("input")).longValue(),
                            output = ((Number) p.get("output")).longValue();
                    inputTokens += in;
                    outputTokens += output;
                    custom(
                            "token_usage",
                            Map.of(
                                    "delta",
                                    tokens(in, output),
                                    "cumulative",
                                    tokens(inputTokens, outputTokens)));
                }
            }
            case "tool.started" ->
                    custom(
                            "harness.tool.started",
                            Map.of("name", p.get("name"), "mode", p.get("mode")));
            case "approval.required" -> {
                UUID id = UUID.fromString(p.get("approvalId").toString());
                interrupt(id, proposals.apply(id));
                return true;
            }
            case "tool.completed" -> {
                if (resumed != null) toolResult(Map.of("receipt", p.get("receipt")));
            }
            case "run.completed" -> {
                if (resumed == null) canonical((String) p.get("text"));
                finish(Map.of("type", "success"));
                return true;
            }
            case "run.cancelled", "run.failed" -> {
                var code = p.getOrDefault("code", "CANCELLED").toString();
                if (resumed != null) toolResult(Map.of("code", code));
                error(code);
                return true;
            }
            default -> {
                /* Internal checkpoints have no UI event. */
            }
        }
        return false;
    }

    private void status(String type) throws IOException {
        custom(
                "harness.run.status",
                Map.of("runId", run, "status", type.substring(4), "attempt", attempt));
    }

    private void emitState() throws IOException {
        emit(
                "STATE_SNAPSHOT",
                Map.of("snapshot", Map.of("tasks", tasks == null ? List.of() : tasks)));
    }

    private void toolResult(Map<String, ?> value) throws IOException {
        var tool = resumed.get("toolCallId");
        emit(
                "TOOL_CALL_RESULT",
                Map.of(
                        "messageId",
                        tool + "-result",
                        "role",
                        "tool",
                        "toolCallId",
                        tool,
                        "content",
                        mapper.writeValueAsString(value)));
    }

    private void canonical(String text) throws IOException {
        String collected = draft.toString();
        if (text.startsWith(collected)) delta(text.substring(collected.length()));
        else {
            closeText();
            snapshot();
        }
    }

    private void interrupt(UUID id, Map<String, Object> proposal) throws IOException {
        canonical((String) proposal.get("text"));
        if (draft.isEmpty() && !open) {
            emit("TEXT_MESSAGE_START", Map.of("messageId", messageId(), "role", "assistant"));
            open = true;
        }
        closeText();
        Object tool = proposal.get("toolCallId"), hash = proposal.get("argsHash");
        String args = arguments(proposal);
        emit(
                "TOOL_CALL_START",
                Map.of(
                        "toolCallId",
                        tool,
                        "toolCallName",
                        "publish_text",
                        "parentMessageId",
                        messageId()));
        emit("TOOL_CALL_ARGS", Map.of("toolCallId", tool, "delta", args));
        emit("TOOL_CALL_END", Map.of("toolCallId", tool));
        snapshot();
        emitState();
        var schema =
                Map.of(
                        "type",
                        "object",
                        "required",
                        List.of("approved", "argsHash"),
                        "additionalProperties",
                        false,
                        "properties",
                        Map.of(
                                "approved",
                                Map.of("type", "boolean"),
                                "argsHash",
                                Map.of("type", "string", "pattern", "^[a-f0-9]{64}$")));
        var interrupt =
                Map.of(
                        "id",
                        id,
                        "reason",
                        "tool_call",
                        "toolCallId",
                        tool,
                        "message",
                        "发布 response.txt？",
                        "expiresAt",
                        proposal.get("expiresAt"),
                        "responseSchema",
                        schema,
                        "metadata",
                        Map.of(
                                "ag-ui:tool-execution",
                                "server",
                                "argsHash",
                                hash,
                                "canDecide",
                                proposal.get("canDecide")));
        finish(Map.of("type", "interrupt", "interrupts", List.of(interrupt)));
    }

    private String arguments(Map<String, Object> proposal) throws IOException {
        // JSON is also the string-valued tool arguments in messages. Order must survive JVM
        // restarts.
        return mapper.writeValueAsString(
                new TreeMap<>(
                        Map.of(
                                "fileName",
                                "response.txt",
                                "bytes",
                                proposal.get("bytes"),
                                "argsHash",
                                proposal.get("argsHash"))));
    }

    @SuppressWarnings("unchecked")
    private void snapshot() throws IOException {
        var messages = new ArrayList<Map<String, Object>>();
        Map<String, Object> reply = null;
        StringBuilder text = null;
        Object pendingTool = null;
        Object snapshotTasks = List.of();
        for (var event : history.apply(sequence)) {
            String id = event.get("runId") + "-" + event.get("attempt");
            var payload = (Map<String, Object>) event.get("payload");
            String type = (String) event.get("type");
            if (type.equals("run.accepted")) {
                messages.add(
                        Map.of(
                                "id",
                                Objects.requireNonNullElse(
                                        event.get("messageId"), event.get("runId") + "-input"),
                                "role",
                                "user",
                                "content",
                                event.get("input")));
                reply = null;
                pendingTool = null;
                continue;
            }
            if (type.equals("plan.updated")) {
                snapshotTasks = payload.get("tasks");
                continue;
            }
            if (type.equals("text.delta")
                    || type.equals("run.completed")
                    || type.equals("approval.required")) {
                if (reply == null || !reply.get("id").equals(id)) {
                    reply =
                            new LinkedHashMap<>(
                                    Map.of("id", id, "role", "assistant", "content", ""));
                    messages.add(reply);
                    text = new StringBuilder();
                }
                if (type.equals("text.delta")) {
                    text.append(payload.get("text"));
                    // Materialize once at the boundary, even for long streams of small deltas.
                    reply.put("content", text);
                } else if (type.equals("run.completed")) reply.put("content", payload.get("text"));
                else {
                    var proposal =
                            proposals.apply(UUID.fromString(payload.get("approvalId").toString()));
                    pendingTool = proposal.get("toolCallId");
                    reply.put("content", proposal.get("text"));
                    reply.put(
                            "toolCalls",
                            List.of(
                                    Map.of(
                                            "id",
                                            pendingTool,
                                            "type",
                                            "function",
                                            "function",
                                            Map.of(
                                                    "name",
                                                    "publish_text",
                                                    "arguments",
                                                    arguments(proposal)))));
                }
            }
            if (pendingTool != null
                    && (type.equals("tool.completed")
                            || type.equals("run.failed")
                            || type.equals("run.cancelled"))) {
                var value =
                        type.equals("tool.completed")
                                ? Map.of("receipt", pendingTool)
                                : Map.of("code", payload.getOrDefault("code", "CANCELLED"));
                messages.add(
                        Map.of(
                                "id",
                                pendingTool + "-result",
                                "role",
                                "tool",
                                "toolCallId",
                                pendingTool,
                                "content",
                                mapper.writeValueAsString(value)));
                pendingTool = null;
            }
        }
        tasks = snapshotTasks;
        emit("MESSAGES_SNAPSHOT", Map.of("messages", messages));
    }
}
