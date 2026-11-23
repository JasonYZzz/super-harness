package io.superharness.api;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.superharness.contract.AguiInput;

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

    void acknowledge(UUID task) throws IOException {
        start();
        finish(Map.of("type", "success"), Map.of("taskId", task));
    }

    void threadSnapshot(Optional<AguiThreads.ThreadView> snapshot) throws IOException {
        start();
        if (snapshot.isEmpty()) {
            finish(Map.of("type", "success"), Map.of("unchanged", true));
            return;
        }
        var view = snapshot.get();
        emit("MESSAGES_SNAPSHOT", Map.of("messages", view.messages()));
        emit("STATE_SNAPSHOT", Map.of("snapshot", view.state()));
        var metadata =
                Map.of(
                        "cursor",
                        view.cursor(),
                        "turns",
                        view.turns(),
                        "tools",
                        view.tools(),
                        "archived",
                        view.archived(),
                        "revisionId",
                        view.revisionId(),
                        "capabilities",
                        view.capabilities());
        var outcome =
                view.interrupts().isEmpty()
                        ? Map.of("type", "success")
                        : Map.of("type", "interrupt", "interrupts", view.interrupts());
        finish(outcome, metadata);
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
        finish(outcome, null);
    }

    private void finish(Map<String, ?> outcome, Map<String, ?> result) throws IOException {
        closeText();
        var fields = new LinkedHashMap<String, Object>();
        fields.put("threadId", input.threadId());
        fields.put("runId", input.runId());
        fields.put("outcome", outcome);
        if (result != null) fields.put("result", result);
        emit("RUN_FINISHED", fields);
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

    private void openText() throws IOException {
        if (!open) {
            emit("TEXT_MESSAGE_START", Map.of("messageId", messageId(), "role", "assistant"));
            open = true;
        }
    }

    private void delta(String text) throws IOException {
        if (text.isEmpty()) return;
        openText();
        draft.append(text);
        emit("TEXT_MESSAGE_CONTENT", Map.of("messageId", messageId(), "delta", text));
    }

    private void custom(String name, Object value) throws IOException {
        emit("CUSTOM", Map.of("name", name, "value", value));
    }

    private static Map<String, Long> tokens(long in, long output) {
        return Map.of("inputTokens", in, "outputTokens", output, "totalTokens", in + output);
    }

    /** A fresh observer has no local user message or history to seed the SDK reducer. */
    boolean acceptForReader(Map<String, Object> row) throws IOException {
        boolean terminal = accept(row);
        if ("run.accepted".equals(row.get("type"))) {
            snapshot();
            emitState();
        }
        return terminal;
    }

    @SuppressWarnings("unchecked")
    boolean accept(Map<String, Object> row) throws IOException {
        String type = row.get("type").toString();
        sequence = ((Number) row.get("seq")).longValue();
        int next = ((Number) row.get("attempt")).intValue();
        timestamp = Instant.parse(row.get("createdAt").toString()).toEpochMilli();
        // A resume starts after the interrupt. Its client can hold a plan that this
        // projection never saw, so a rolled-back attempt must still publish its state.
        boolean rolledBack =
                Set.of("run.failed", "run.cancelled", "run.recovery_required").contains(type);
        boolean resetPlan =
                (tasks != null && attempt != next)
                        || (rolledBack && (tasks != null || resumed != null));
        if (attempt != next) {
            closeText();
            attempt = next;
            draft.setLength(0);
        }
        var p = (Map<String, Object>) row.get("payload");
        if (resetPlan) {
            tasks = AguiHistory.read(history.apply(sequence), proposals, mapper).tasks();
            emitState();
        }
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
            case "tool.started" -> {
                openText();
                custom("harness.tool.started", AguiHistory.tool(row, p));
            }
            case "approval.required" -> {
                UUID id = UUID.fromString(p.get("approvalId").toString());
                interrupt(id, proposals.apply(id));
                return true;
            }
            case "tool.completed" -> {
                if (resumed != null)
                    toolResult(resumed.get("toolCallId"), Map.of("receipt", p.get("receipt")));
            }
            case "run.completed" -> {
                if (resumed == null) canonical((String) p.get("text"));
                finish(Map.of("type", "success"));
                return true;
            }
            case "run.cancelled", "run.failed" -> {
                var code = p.getOrDefault("code", "CANCELLED").toString();
                if (resumed != null) toolResult(resumed.get("toolCallId"), Map.of("code", code));
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

    private void toolResult(Object tool, Map<String, ?> value) throws IOException {
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

    void reviewReceipt(UUID tool, boolean approved) throws IOException {
        start();
        if (approved) {
            toolResult(tool, Map.of("receipt", tool));
            finish(Map.of("type", "success"));
        } else {
            toolResult(tool, Map.of("code", "APPROVAL_REJECTED"));
            error("APPROVAL_REJECTED");
        }
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
        if (draft.isEmpty()) openText();
        closeText();
        Object tool = proposal.get("toolCallId");
        String args = AguiHistory.arguments(mapper, proposal);
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
        finish(
                Map.of(
                        "type",
                        "interrupt",
                        "interrupts",
                        List.of(AguiHistory.interrupt(id, proposal))));
    }

    private void snapshot() throws IOException {
        var view = AguiHistory.read(history.apply(sequence), proposals, mapper);
        tasks = view.tasks();
        emit("MESSAGES_SNAPSHOT", Map.of("messages", view.messages()));
    }
}
