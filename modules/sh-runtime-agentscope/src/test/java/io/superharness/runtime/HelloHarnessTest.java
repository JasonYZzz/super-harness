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

    @Test
    void compactsHistoryInTheNextPublishedSlot() {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        var store = JdbcDistributedStore.create(ds);
        var runtime = new HarnessRuntime(store, workspace, "");
        var conversation = UUID.randomUUID();
        var old = UUID.randomUUID();
        var slot = UUID.randomUUID();
        var history =
                AgentState.builder().userId(conversation.toString()).sessionId(old.toString());
        for (int i = 0; i < 60; i++) {
            history.addMessage(new UserMessage("prior message " + i + " background".repeat(100)));
            history.addMessage(new AssistantMessage("recorded " + i));
        }
        store.agentStateStore()
                .save(conversation.toString(), old.toString(), "agent_state", history.build());
        String modelName = "compact-" + UUID.randomUUID();
        ModelRegistry.register(
                modelName,
                new Model() {
                    public String getModelName() {
                        return modelName;
                    }

                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
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
        assertTrue(compact.size() < 120);
        assertTrue(compact.stream().anyMatch(m -> "__compaction_summary__".equals(m.getName())));
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
