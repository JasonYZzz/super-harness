package io.superharness.api;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.*;
import java.util.function.Function;

/** Thread view folded from committed events in conversation and sequence order. */
record AguiHistory(
        List<Map<String, Object>> messages,
        Object tasks,
        Map<String, Object> interrupt,
        List<Map<String, Object>> tools) {
    static Map<String, Object> interrupt(UUID id, Map<String, Object> proposal) {
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
        String text = (String) proposal.get("text");
        return Map.of(
                "id",
                id,
                "reason",
                "tool_call",
                "toolCallId",
                proposal.get("toolCallId"),
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
                        proposal.get("argsHash"),
                        "preview",
                        text.substring(0, Math.min(1000, text.length())),
                        "canDecide",
                        proposal.get("canDecide")));
    }

    static String arguments(ObjectMapper mapper, Map<String, Object> proposal) throws IOException {
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

    static Map<String, Object> tool(Map<String, Object> event, Map<String, Object> payload) {
        String message = event.get("runId") + "-" + event.get("attempt");
        return Map.of(
                "id",
                message + "-tool-" + event.get("seq"),
                "messageId",
                message,
                "name",
                payload.get("name"),
                "mode",
                payload.get("mode"));
    }

    @SuppressWarnings("unchecked")
    static AguiHistory read(
            List<Map<String, Object>> events,
            Function<UUID, Map<String, Object>> proposals,
            ObjectMapper mapper)
            throws IOException {
        var messages = new ArrayList<Map<String, Object>>();
        Map<String, Object> reply = null;
        StringBuilder text = null;
        Object pendingTool = null;
        Object snapshotTasks = List.of();
        Object previousTasks = List.of();
        Object currentAttempt = null;
        Map<String, Object> interrupt = null;
        var tools = new ArrayList<Map<String, Object>>();
        for (var event : events) {
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
                previousTasks = snapshotTasks;
                currentAttempt = event.get("attempt");
                reply = null;
                pendingTool = null;
                continue;
            }
            if (!Objects.equals(currentAttempt, event.get("attempt"))
                    || type.equals("run.recovery_required")
                    || type.equals("run.failed")
                    || type.equals("run.cancelled")) {
                snapshotTasks = previousTasks;
                currentAttempt = event.get("attempt");
            }
            if (type.equals("tool.started")) {
                tools.add(tool(event, payload));
            }
            if (type.equals("plan.updated")) {
                snapshotTasks = payload.get("tasks");
                continue;
            }
            if (type.equals("text.delta")
                    || type.equals("run.completed")
                    || type.equals("approval.required")
                    || type.equals("tool.started")) {
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
                else if (type.equals("approval.required")) {
                    var proposal =
                            proposals.apply(UUID.fromString(payload.get("approvalId").toString()));
                    pendingTool = proposal.get("toolCallId");
                    interrupt =
                            interrupt(
                                    UUID.fromString(payload.get("approvalId").toString()),
                                    proposal);
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
                                                    arguments(mapper, proposal)))));
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
                interrupt = null;
            }
        }
        return new AguiHistory(messages, snapshotTasks, interrupt, tools);
    }
}
