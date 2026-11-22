package io.superharness.runtime;

import static org.junit.jupiter.api.Assertions.*;

import io.agentscope.extensions.jdbc.JdbcDistributedStore;
import io.superharness.runtimespi.AgentRuntime.Turn;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

class SandboxIT {
    @TempDir Path root;

    @Test
    void reloadsPublishedSnapshotIntoANewAttempt() throws Exception {
        var db = new JdbcDataSource();
        db.setURL("jdbc:h2:mem:sandbox;DB_CLOSE_DELAY=-1");
        var store = JdbcDistributedStore.create(db);
        var runtime = new HarnessRuntime(store, root, System.getenv("SH_SANDBOX_IMAGE"));
        var conversation = UUID.randomUUID();
        UUID previous = null;
        for (String input : java.util.List.of("one", "two")) {
            var slot = UUID.randomUUID();
            try (var handle =
                    runtime.prepare(
                            new Turn(
                                    conversation,
                                    slot,
                                    previous,
                                    "Answer briefly.",
                                    "local-echo",
                                    input,
                                    true,
                                    null),
                            new io.superharness.runtimespi.RuntimeMeter() {
                                public UUID reserve(int input, int output) {
                                    return UUID.randomUUID();
                                }

                                public void record(UUID call, int input, int output) {}

                                public void tool(String tool) {}
                            })) {
                assertEquals("收到：" + input, handle.execute(delta -> {}));
            }
            assertTrue(store.sandboxSnapshotSpec().build("workspace-" + slot).isRestorable());
            previous = slot;
        }
    }

    @Test
    void workspaceBytesSurviveOnlyThePublishingConversation() throws Exception {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:sandbox-isolation;DB_CLOSE_DELAY=-1");
        var store = JdbcDistributedStore.create(ds);
        var runtime = new HarnessRuntime(store, root, System.getenv("SH_SANDBOX_IMAGE"));
        var modelName = "workspace-fixture-" + UUID.randomUUID();
        var marker = "private-" + UUID.randomUUID();
        io.agentscope.core.model.ModelRegistry.register(
                modelName,
                new io.agentscope.core.model.Model() {
                    public String getModelName() {
                        return modelName;
                    }

                    public reactor.core.publisher.Flux<io.agentscope.core.model.ChatResponse>
                            stream(
                                    java.util.List<io.agentscope.core.message.Msg> messages,
                                    java.util.List<io.agentscope.core.model.ToolSchema> tools,
                                    io.agentscope.core.model.GenerateOptions options) {
                        var last = messages.get(messages.size() - 1);
                        io.agentscope.core.message.ContentBlock response;
                        if (last.hasContentBlocks(
                                io.agentscope.core.message.ToolResultBlock.class)) {
                            String result =
                                    last
                                            .getContentBlocks(
                                                    io.agentscope.core.message.ToolResultBlock
                                                            .class)
                                            .stream()
                                            .flatMap(block -> block.getOutput().stream())
                                            .filter(
                                                    io.agentscope.core.message.TextBlock.class
                                                            ::isInstance)
                                            .map(
                                                    block ->
                                                            ((io.agentscope.core.message.TextBlock)
                                                                            block)
                                                                    .getText())
                                            .collect(java.util.stream.Collectors.joining());
                            response =
                                    io.agentscope.core.message.TextBlock.builder()
                                            .text(result)
                                            .build();
                        } else {
                            boolean write = last.getTextContent().equals("save");
                            boolean edit = last.getTextContent().equals("edit");
                            var args =
                                    write
                                            ? java.util.Map.<String, Object>of(
                                                    "path", "note.txt", "content", marker)
                                            : edit
                                                    ? java.util.Map.<String, Object>of(
                                                            "path",
                                                            "note.txt",
                                                            "old_string",
                                                            marker,
                                                            "new_string",
                                                            "修改后的内容")
                                                    : java.util.Map.<String, Object>of(
                                                            "path", "note.txt");
                            response =
                                    new io.agentscope.core.message.ToolUseBlock(
                                            UUID.randomUUID().toString(),
                                            write ? "write_file" : edit ? "edit_file" : "read_file",
                                            args);
                        }
                        return reactor.core.publisher.Flux.just(
                                io.agentscope.core.model.ChatResponse.builder()
                                        .content(java.util.List.of(response))
                                        .build());
                    }
                });
        var meter =
                new io.superharness.runtimespi.RuntimeMeter() {
                    public UUID reserve(int in, int out) {
                        return UUID.randomUUID();
                    }

                    public void record(UUID call, int in, int out) {}

                    public void tool(String name) {}
                };
        var conversation = UUID.randomUUID();
        var saved = UUID.randomUUID();
        try (var handle =
                runtime.prepare(
                        new Turn(
                                conversation,
                                saved,
                                null,
                                "use tools",
                                modelName,
                                "save",
                                true,
                                null),
                        meter)) {
            String written = handle.execute(delta -> {});
            assertTrue(written.contains("Written"), written);
        }
        var edited = UUID.randomUUID();
        try (var handle =
                runtime.prepare(
                        new Turn(
                                conversation,
                                edited,
                                saved,
                                "use tools",
                                modelName,
                                "edit",
                                true,
                                null),
                        meter)) {
            String result = handle.execute(delta -> {});
            assertTrue(result.contains("1 replacement"), result);
        }
        for (boolean published : java.util.List.of(true, false)) {
            var turn =
                    new Turn(
                            published ? conversation : UUID.randomUUID(),
                            UUID.randomUUID(),
                            published ? edited : null,
                            "use tools",
                            modelName,
                            "read",
                            true,
                            null);
            try (var handle = runtime.prepare(turn, meter)) {
                assertEquals(published, handle.execute(delta -> {}).contains("修改后的内容"));
            }
        }
    }
}
