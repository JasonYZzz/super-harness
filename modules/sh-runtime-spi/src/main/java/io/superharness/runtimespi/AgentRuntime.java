package io.superharness.runtimespi;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * A turn writes only to its unique session slot. Publication of that slot is fenced by the engine.
 */
public interface AgentRuntime {
    record PinnedSkill(UUID id, String name, String version, String content,
            String description, java.util.Map<String, String> resources) {
        public PinnedSkill {
            resources = java.util.Map.copyOf(resources);
        }
    }

    record Recall(java.util.List<String> recent, UUID summaryId, String summary) {
        public static final Recall EMPTY = new Recall(java.util.List.of(), null, "");

        public Recall {
            recent = java.util.List.copyOf(recent);
        }
    }

    record Turn(
            UUID conversation,
            UUID slot,
            UUID previousSlot,
            String prompt,
            String model,
            String input,
            boolean sandbox,
            McpProfile mcp,
            String permissionMode,
            java.util.List<PinnedSkill> skills,
            boolean memoryEnabled,
            Recall recall, UUID libraryId) {
        public Turn {
            skills = java.util.List.copyOf(skills);
        }

        public Turn(
                UUID conversation,
                UUID slot,
                UUID previousSlot,
                String prompt,
                String model,
                String input,
                boolean sandbox,
                McpProfile mcp,
                String permissionMode,
                java.util.List<PinnedSkill> skills,
                boolean memoryEnabled) {
            this(
                    conversation,
                    slot,
                    previousSlot,
                    prompt,
                    model,
                    input,
                    sandbox,
                    mcp,
                    permissionMode,
                    skills,
                    memoryEnabled,
                    Recall.EMPTY, null);
        }

        public Turn(
                UUID conversation,
                UUID slot,
                UUID previousSlot,
                String prompt,
                String model,
                String input,
                boolean sandbox,
                McpProfile mcp,
                String permissionMode) {
            this(
                    conversation,
                    slot,
                    previousSlot,
                    prompt,
                    model,
                    input,
                    sandbox,
                    mcp,
                    permissionMode,
                    java.util.List.of(),
                    false,
                    Recall.EMPTY, null);
        }

        public Turn(
                UUID conversation,
                UUID slot,
                UUID previousSlot,
                String prompt,
                String model,
                String input,
                boolean sandbox,
                McpProfile mcp) {
            this(conversation, slot, previousSlot, prompt, model, input, sandbox, mcp, "default");
        }
    }

    interface Handle extends AutoCloseable {
        String execute(Consumer<String> onTextDelta);

        void cancel();

        void close();
    }

    Handle prepare(Turn turn, RuntimeMeter meter);
}
