package io.superharness.api;

import io.superharness.domain.Problem;

import java.util.*;

/** Only the last user message or a single durable publish decision is an execution command. */
record AguiInput(
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

    void validate() {
        if (threadId == null || runId == null || runId.isBlank() || runId.length() > 180)
            throw new Problem(400, "INVALID_RUN_IDENTIFIERS");
        if (tools != null && !tools.isEmpty()) throw new Problem(400, "UNSUPPORTED_FRONTEND_TOOLS");
        if (context != null && !context.isEmpty()) throw new Problem(400, "UNSUPPORTED_CONTEXT");
        if (forwardedProps != null && !forwardedProps.isEmpty())
            throw new Problem(400, "UNSUPPORTED_FORWARDED_PROPS");
        if (parentRunId != null) throw new Problem(400, "UNSUPPORTED_BRANCH");
    }

    record Message(String id, String text) {}

    Message userMessage() {
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

    record Decision(UUID approval, boolean cancelled, boolean approved, String hash) {}

    Decision decision() {
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
