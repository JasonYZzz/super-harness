package io.superharness.runtime;

import static org.junit.jupiter.api.Assertions.*;

import io.agentscope.core.agent.*;
import io.agentscope.core.event.*;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import io.agentscope.core.state.*;
import io.agentscope.extensions.jdbc.JdbcDistributedStore;
import io.agentscope.harness.agent.HarnessAgent;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

class HelloHarnessTest {
    @TempDir Path workspace;
    private final RuntimeContext context =
            RuntimeContext.builder().userId("tenant-user").sessionId("room").build();

    HarnessAgent agent(Model model) {
        return HarnessAgent.builder()
                .name("hello")
                .model(model)
                .workspace(workspace)
                .stateStore(new InMemoryAgentStateStore())
                .disableMemoryHooks()
                .disableShellTool()
                .build();
    }

    @Test
    void typedResultsAndSecondTurnHistory() {
        var seen = new AtomicReference<List<Msg>>();
        Model model =
                new Model() {
                    public String getModelName() {
                        return "recording";
                    }

                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        seen.set(List.copyOf(messages));
                        return new LocalModel().stream(messages, tools, options);
                    }
                };
        try (var harness = agent(model)) {
            for (String turn : List.of("first marker", "second marker")) {
                var events =
                        harness.streamEvents(new UserMessage(turn), context)
                                .collectList()
                                .block(Duration.ofSeconds(10));
                assertTrue(events.stream().anyMatch(AgentResultEvent.class::isInstance));
            }
            assertTrue(
                    seen.get().stream().anyMatch(m -> "first marker".equals(m.getTextContent())));
            var queued = harness.prepareRun(List.of(new UserMessage("stop")), context);
            assertTrue(queued.interrupt());
            assertEquals(AgentRun.Status.CANCELLED, queued.status());
        }
    }

    @Test
    void runningHandleSupportsCooperativeAndHardStop() throws Exception {
        for (boolean hard : List.of(false, true)) {
            var started = new CountDownLatch(1);
            var response = new CompletableFuture<ChatResponse>();
            Model waiting =
                    new Model() {
                        public String getModelName() {
                            return "waiting";
                        }

                        public Flux<ChatResponse> stream(
                                List<Msg> messages,
                                List<ToolSchema> tools,
                                GenerateOptions options) {
                            started.countDown();
                            return reactor.core.publisher.Mono.fromFuture(response).flux();
                        }
                    };
            try (var harness = agent(waiting)) {
                var handle = harness.prepareRun(List.of(new UserMessage("wait")), context);
                var done = handle.stream().then().toFuture();
                assertTrue(started.await(10, TimeUnit.SECONDS));
                if (hard) {
                    assertTrue(handle.cancel());
                    assertThrows(CancellationException.class, () -> done.get(5, TimeUnit.SECONDS));
                } else {
                    assertTrue(handle.interrupt());
                    response.complete(
                            ChatResponse.builder()
                                    .content(List.of(TextBlock.builder().text("done").build()))
                                    .build());
                    done.get(10, TimeUnit.SECONDS);
                }
                assertTrue(handle.status().isTerminal());
            }
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"normal", "overflow", "partial", "repeated"})
    void compactsHistoryWithoutExtractingFactsOrArchiving(String scenario) throws Exception {
        boolean overflow = !scenario.equals("normal");
        var summaries = new java.util.concurrent.atomic.AtomicInteger();
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        var store = JdbcDistributedStore.create(ds);
        var runtime = new HarnessRuntime(store, workspace, "");
        var conversation = UUID.randomUUID();
        var old = UUID.randomUUID();
        var slot = UUID.randomUUID();
        var history =
                AgentState.builder().userId(conversation.toString()).sessionId(old.toString());
        int pairs = overflow ? 5 : 60;
        for (int i = 0; i < pairs; i++) {
            history.addMessage(new UserMessage("prior message " + i + " background".repeat(100)));
            history.addMessage(new AssistantMessage("recorded " + i));
        }
        store.agentStateStore()
                .save(conversation.toString(), old.toString(), "agent_state", history.build());
        var overflows = new java.util.concurrent.atomic.AtomicInteger();
        String modelName = "compact-" + UUID.randomUUID();
        ModelRegistry.register(
                modelName,
                new Model() {
                    public String getModelName() {
                        return modelName;
                    }

                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        if (messages.getFirst()
                                .getTextContent()
                                .contains("memory extraction assistant"))
                            throw new AssertionError("Fact extraction while memory disabled");
                        if (tools == null) summaries.incrementAndGet();
                        boolean compacted =
                                messages.stream()
                                        .anyMatch(
                                                m -> "__compaction_summary__".equals(m.getName()));
                        if (overflow
                                && tools != null
                                && (!compacted || scenario.equals("repeated"))) {
                            overflows.incrementAndGet();
                            Flux<ChatResponse> failure =
                                    Flux.error(
                                            new IllegalArgumentException(
                                                    "context_length_exceeded"));
                            if (scenario.equals("partial"))
                                return Flux.just(
                                                ChatResponse.builder()
                                                        .content(
                                                                List.of(
                                                                        TextBlock.builder()
                                                                                .text("partial")
                                                                                .build()))
                                                        .build())
                                        .concatWith(failure);
                            return failure;
                        }
                        return Flux.just(
                                ChatResponse.builder()
                                        .content(
                                                List.of(
                                                        TextBlock.builder()
                                                                .text("Brief summary")
                                                                .build()))
                                        .build());
                    }
                });
        var turn =
                new io.superharness.runtimespi.AgentRuntime.Turn(
                        conversation, slot, old, "test", modelName, "continue", false, null);
        var meter =
                new io.superharness.runtimespi.RuntimeMeter() {
                    public UUID reserve(int input, int output) {
                        return UUID.randomUUID();
                    }

                    public void record(UUID id, int input, int output) {}

                    public void tool(String name) {}
                };
        try (var handle = runtime.prepare(turn, meter)) {
            if (scenario.equals("partial") || scenario.equals("repeated")) {
                assertThrows(RuntimeException.class, () -> handle.execute(delta -> {}));
                assertEquals(scenario.equals("partial") ? 0 : 1, summaries.get());
                return;
            }
            handle.execute(delta -> {});
        }
        var compact =
                store.agentStateStore()
                        .get(
                                conversation.toString(),
                                slot.toString(),
                                "agent_state",
                                AgentState.class)
                        .orElseThrow()
                        .getContext();
        assertTrue(compact.size() < pairs * 2);
        if (overflow) assertTrue(overflows.get() > 0);
        try (var connection = ds.getConnection();
                var stmt = connection.createStatement();
                var rows =
                        stmt.executeQuery(
                                "SELECT COUNT(*) FROM AGENTSCOPE_STORE WHERE ITEM_KEY LIKE"
                                        + " '%.jsonl' OR ITEM_KEY LIKE '%.md'")) {
            assertTrue(rows.next());
            assertEquals(0, rows.getInt(1));
        }
        assertTrue(compact.stream().anyMatch(m -> "__compaction_summary__".equals(m.getName())));
    }

    @Test
    void evictedOutputIsPageableAcrossPublishedRemoteSlots() {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        var store = JdbcDistributedStore.create(ds);
        var source = UUID.randomUUID();
        var spec =
                new io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec(
                                store.baseStore())
                        .addSharedPrefix("large_tool_results/")
                        .isolationScope(io.agentscope.harness.agent.IsolationScope.SESSION);
        var fs = spec.toFilesystem(workspace, "assistant", rc -> List.of(rc.getSessionId()));
        var config =
                io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig.builder()
                        .evictionPath("large_tool_results/" + source)
                        .build();
        var eviction =
                new io.agentscope.harness.agent.middleware.ToolResultEvictionMiddleware(fs, config);
        String body = "start" + "x".repeat(81000) + "end";
        var tool =
                Msg.builder()
                        .role(MsgRole.TOOL)
                        .content(
                                new ToolResultBlock(
                                        "lookup-1",
                                        "lookup",
                                        List.of(TextBlock.builder().text(body).build()),
                                        Map.of(),
                                        ToolResultState.SUCCESS))
                        .build();
        var state = AgentState.builder().addMessage(tool).build();
        var context =
                RuntimeContext.builder()
                        .userId("conversation")
                        .sessionId(source.toString())
                        .agentState(state)
                        .build();
        var input =
                new io.agentscope.core.middleware.ReasoningInput(List.of(tool), List.of(), null);
        try (var harness =
                HarnessAgent.builder()
                        .name("assistant")
                        .model(new LocalModel())
                        .workspace(workspace)
                        .build()) {
            eviction.onReasoning(harness, context, input, updated -> Flux.empty()).then().block();
        }
        var result =
                state.getContext().getFirst().getContentBlocks(ToolResultBlock.class).getFirst();
        String preview =
                result.getOutput().stream()
                        .filter(TextBlock.class::isInstance)
                        .map(TextBlock.class::cast)
                        .map(TextBlock::getText)
                        .collect(java.util.stream.Collectors.joining());
        var match =
                java.util.regex.Pattern.compile("`(large_tool_results/[^`]+)`").matcher(preview);
        assertTrue(match.find(), preview.substring(0, 100));
        var published = new java.util.concurrent.atomic.AtomicBoolean(false);
        var reader =
                new ToolOutputReader(
                        fs,
                        new io.superharness.runtimespi.RuntimeMeter() {
                            public UUID reserve(int input, int output) {
                                return UUID.randomUUID();
                            }

                            public void record(UUID id, int input, int output) {}

                            public void tool(String name) {}

                            public boolean publishedSlot(UUID id) {
                                return published.get() && id.equals(source);
                            }
                        });
        assertTrue(reader.read(context, match.group(1), 0).startsWith(body.substring(0, 4096)));
        var later =
                RuntimeContext.builder()
                        .userId("conversation")
                        .sessionId(UUID.randomUUID().toString())
                        .build();
        assertEquals("Unknown tool output.", reader.read(later, match.group(1), 81000));
        published.set(true);
        assertEquals(body.substring(81000), reader.read(later, match.group(1), 81000));
        assertEquals("Unknown tool output.", reader.read(context, "MEMORY.md", 0));
    }

    @Test
    void jdbcH2() throws Exception {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        verifyStore(ds);
    }

    static void verifyStore(javax.sql.DataSource ds) throws Exception {
        verifyStore(() -> JdbcDistributedStore.create(ds));
    }

    static void verifyStore(
            java.util.function.Supplier<io.agentscope.harness.agent.DistributedStore> open)
            throws Exception {
        var runtime = open.get();
        var store = runtime.agentStateStore();
        var state = AgentState.builder().build();
        state.setSummary("saved");
        long version = store.saveIfVersion("u", "s", "k", state, 0);
        assertTrue(version > 0);
        var reader = open.get().agentStateStore();
        assertEquals(
                "saved", reader.getVersioned("u", "s", "k", AgentState.class).value().getSummary());
        assertEquals(AgentStateStore.UNVERSIONED, reader.saveIfVersion("u", "s", "k", state, 0));
        var namespace = List.of("tenant", "conversation");
        runtime.baseStore().put(namespace, "notes", Map.of("body", "hello"));
        var reopened = open.get();
        assertEquals("hello", reopened.baseStore().get(namespace, "notes").value().get("body"));
        byte[] archive = {1, 2, 3};
        runtime.sandboxSnapshotSpec().build("s").persist(new java.io.ByteArrayInputStream(archive));
        try (var input = reopened.sandboxSnapshotSpec().build("s").restore()) {
            assertArrayEquals(archive, input.readAllBytes());
        }
        var key =
                io.agentscope.harness.agent.sandbox.SandboxIsolationKey.resolve(
                                io.agentscope.harness.agent.IsolationScope.SESSION,
                                RuntimeContext.builder().sessionId("s").build(),
                                "test")
                        .orElseThrow();
        try (var first = runtime.sandboxExecutionGuard().tryEnter(key)) {
            assertNotNull(first);
        }
        try (var second = reopened.sandboxExecutionGuard().tryEnter(key)) {
            assertNotNull(second);
        }
    }
}
