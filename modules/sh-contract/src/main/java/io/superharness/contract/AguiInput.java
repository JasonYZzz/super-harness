package io.superharness.contract;

import io.superharness.domain.Problem;

import java.util.*;

/** AG-UI input for a durable conversation and its authorized task operations. */
public record AguiInput(
        UUID threadId,
        String runId,
        List<Map<String, Object>> messages,
        List<Map<String, Object>> resume,
        List<?> tools,
        List<?> context,
        Map<String, Object> forwardedProps,
        Object state,
        String protocolVersion,
        String parentRunId) {

    public sealed interface Extension permits Snapshot, TaskOperation {}

    public sealed interface TaskOperation extends Extension permits Observe, Cancel, Recover {
        UUID task();
    }

    public record Snapshot(String since) implements Extension {}

    public record Observe(UUID task) implements TaskOperation {}

    public record Cancel(UUID task) implements TaskOperation {}

    public record Recover(UUID task, int attempt) implements TaskOperation {}

    public Extension validate() {
        if (threadId == null || runId == null || runId.isBlank() || runId.length() > 180)
            throw new Problem(400, "INVALID_RUN_IDENTIFIERS");
        if (tools != null && !tools.isEmpty()) throw new Problem(400, "UNSUPPORTED_FRONTEND_TOOLS");
        if (context != null && !context.isEmpty()) throw new Problem(400, "UNSUPPORTED_CONTEXT");
        if (parentRunId != null) throw new Problem(400, "UNSUPPORTED_BRANCH");
        if (forwardedProps == null || forwardedProps.isEmpty()) return null;
        if ((messages != null && !messages.isEmpty()) || (resume != null && !resume.isEmpty()))
            throw new Problem(400, "AMBIGUOUS_AGUI_INPUT");
        if (forwardedProps.size() != 1) throw new Problem(400, "UNSUPPORTED_FORWARDED_PROPS");
        var prop = forwardedProps.entrySet().iterator().next();
        return switch (prop.getKey()) {
            case "harness.snapshot" -> {
                if (prop.getValue() instanceof Map<?, ?> options) {
                    if (options.isEmpty()) yield new Snapshot(null);
                    if (options.size() == 1
                            && options.get("since") instanceof String since
                            && since.matches("[a-f0-9]{64}")) yield new Snapshot(since);
                }
                throw new Problem(400, "INVALID_SNAPSHOT_CURSOR");
            }
            case "harness.observe" -> new Observe(taskId(prop.getValue()));
            case "harness.cancel" -> new Cancel(taskId(prop.getValue()));
            case "harness.recover" -> {
                if (!(prop.getValue() instanceof Map<?, ?> args)
                        || args.size() != 2
                        || !(args.get("attempt") instanceof Integer attempt)
                        || attempt < 0) throw new Problem(400, "INVALID_RECOVERY_ATTEMPT");
                yield new Recover(taskId(args.get("taskId")), attempt);
            }
            default -> throw new Problem(400, "UNSUPPORTED_FORWARDED_PROPS");
        };
    }

    private static UUID taskId(Object value) {
        if (value instanceof String id) {
            try {
                UUID parsed = UUID.fromString(id);
                if (parsed.toString().equalsIgnoreCase(id)) return parsed;
            } catch (IllegalArgumentException ignored) {
                // Report all invalid platform identifiers as a protocol input error.
            }
        }
        throw new Problem(400, "INVALID_TASK_ID");
    }

    public record Message(String id, String text) {}

    public Message userMessage() {
        if (messages == null || messages.isEmpty() || messages.getLast() == null)
            throw new Problem(400, "USER_MESSAGE_REQUIRED");
        var last = messages.getLast();
        if (!"user".equals(last.get("role"))
                || !(last.get("id") instanceof String id)
                || id.isBlank()
                || id.length() > 180) throw new Problem(400, "INVALID_USER_MESSAGE");
        Object content = last.get("content");
        String text;
        if (content instanceof String value) text = value;
        else if (content instanceof List<?> parts) {
            var joined = new StringBuilder();
            for (var part : parts) {
                if (!(part instanceof Map<?, ?> block)
                        || !"text".equals(block.get("type"))
                        || !(block.get("text") instanceof String value))
                    throw new Problem(400, "UNSUPPORTED_CONTENT");
                joined.append(value);
            }
            text = joined.toString();
        } else throw new Problem(400, "UNSUPPORTED_CONTENT");
        if (text.isBlank() || text.length() > 32000) throw new Problem(400, "INVALID_TEXT");
        return new Message(id, text);
    }

    public record Decision(UUID approval, boolean cancelled, boolean approved, String hash) {}

    public Decision decision() {
        if (resume.size() != 1 || resume.getFirst() == null)
            throw new Problem(400, "INVALID_RESUME");
        var item = resume.getFirst();
        if (!Set.of("interruptId", "status", "payload", "metadata").containsAll(item.keySet()))
            throw new Problem(400, "INVALID_RESUME_PAYLOAD");
        UUID approval;
        try {
            approval = UUID.fromString(Objects.toString(item.get("interruptId"), ""));
        } catch (IllegalArgumentException ex) {
            throw new Problem(400, "INVALID_INTERRUPT");
        }
        if ("cancelled".equals(item.get("status")) && !item.containsKey("payload"))
            return new Decision(approval, true, false, null);
        if (!"resolved".equals(item.get("status"))
                || !(item.get("payload") instanceof Map<?, ?> p)
                || !p.keySet().equals(Set.of("approved", "argsHash"))
                || !(p.get("approved") instanceof Boolean approved)
                || !(p.get("argsHash") instanceof String hash)
                || !hash.matches("[a-f0-9]{64}")) throw new Problem(400, "INVALID_RESUME_PAYLOAD");
        return new Decision(approval, false, approved, hash);
    }
}
