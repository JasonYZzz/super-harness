package io.superharness.runtime;

import io.agentscope.core.agent.*;
import io.agentscope.core.event.*;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.*;
import io.agentscope.harness.agent.*;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import io.superharness.runtimespi.AgentRuntime;

import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Isolate mutable SDK state by attempt. Only the worker can publish a successfully closed slot. */
public class HarnessRuntime implements AgentRuntime {
    private final DistributedStore store;
    private final Path root;
    private final WorkspaceSandbox sandbox;
    private final Environment modelEnvironment;

    /** Creates a standalone runtime using process environment settings. */
    public HarnessRuntime(DistributedStore store, Path root, String image) {
        this(store, root, image, new StandardEnvironment());
    }

    /** Creates a worker runtime with access to its Spring model configuration. */
    HarnessRuntime(DistributedStore store, Path root, String image, Environment modelEnvironment) {
        this.store = store;
        this.root = root.toAbsolutePath();
        this.sandbox = new WorkspaceSandbox(store, image);
        this.modelEnvironment = modelEnvironment;
        if (!store.agentStateStore().supportsVersioning())
            throw new IllegalArgumentException("A versioned AgentStateStore is required");
    }

    /** Resolves a persisted model ID, using the Token Plan credential only for its provider. */
    private io.agentscope.core.model.Model resolveModel(String model) {
        if (model.startsWith("token-plan:")) {
            String endpoint = modelEnvironment.getProperty("sh.models.token-plan.base-url", "");
            String key = modelEnvironment.getProperty("sh.models.token-plan.api-key", "");
            if (endpoint.isBlank() || key.isBlank())
                throw new IllegalStateException("Token Plan base URL and API key are required");
            var context =
                    io.agentscope.core.model.ModelCreationContext.builder()
                            .baseUrl(endpoint)
                            .apiKey(key)
                            .build();
            return io.agentscope.core.model.ModelRegistry.resolve(
                    "openai:" + model.substring("token-plan:".length()), context);
        }
        String endpoint =
                modelEnvironment.getProperty(
                        "sh.models.openai.base-url",
                        modelEnvironment.getProperty("SH_OPENAI_BASE_URL"));
        if (model.startsWith("openai:") && endpoint != null && !endpoint.isBlank()) {
            var context =
                    io.agentscope.core.model.ModelCreationContext.builder()
                            .baseUrl(endpoint)
                            .cachePolicy(io.agentscope.core.model.CachePolicy.ENABLED)
                            .build();
            return io.agentscope.core.model.ModelRegistry.resolve(model, context);
        }
        return io.agentscope.core.model.ModelRegistry.resolve(model);
    }

    private io.agentscope.core.model.GenerateOptions searchOptions(String model) {
        String configured = modelEnvironment.getProperty("sh.models.web-search-models", "");
        if (Arrays.stream(configured.split(",")).map(String::strip).noneMatch(model::equals))
            return null;
        return io.agentscope.core.model.GenerateOptions.builder()
                .additionalBodyParam("enable_search", true)
                .build();
    }

    static Map<String, Object> planItem(Task task) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", task.getId());
        item.put("subject", task.getSubject());
        item.put("description", task.getDescription());
        item.put("state", task.getState().getWire());
        item.put("created_at", task.getCreatedAt());
        item.put("owner", task.getOwner());
        item.put("metadata", task.getMetadata() == null ? Map.of() : task.getMetadata());
        item.put("blocks", task.getBlocks());
        item.put("blocked_by", task.getBlockedBy());
        return item;
    }

    @Override
    public Handle prepare(Turn turn, io.superharness.runtimespi.RuntimeMeter meter) {
        String user = turn.conversation().toString(), session = turn.slot().toString();
        boolean planning = "plan".equals(turn.permissionMode());
        var permissionMode =
                io.agentscope.core.permission.PermissionMode.fromString(
                        planning ? "default" : turn.permissionMode());
        var states = store.agentStateStore();
        if (turn.previousSlot() != null) {
            var old =
                    states.get(
                                    user,
                                    turn.previousSlot().toString(),
                                    "agent_state",
                                    AgentState.class)
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "Published conversation state is missing"));
            var next =
                    AgentState.builder()
                            .userId(user)
                            .sessionId(session)
                            .summary(old.getSummary())
                            .context(old.getContext())
                            .tasksContext(old.getTasksContext())
                            .planModeContext(old.getPlanModeContext())
                            .permissionContext(old.getPermissionContext().withMode(permissionMode))
                            .build();
            if (states.saveIfVersion(user, session, "agent_state", next, 0)
                    == AgentStateStore.UNVERSIONED)
                throw new IllegalStateException("Attempt slot already populated");
        }
        Path workspace = root.resolve(session);
        try {
            Files.createDirectories(workspace);
            for (var skill : turn.skills()) {
                // SDK directories use the immutable version UUID. Display names never become paths.
                String name = "skill-" + skill.id();
                Path dir = Files.createDirectories(workspace.resolve("skills").resolve(name));
                String description =
                        io.agentscope.core.util.JsonUtils.getJsonCodec()
                                .toJson(skill.name() + " (" + skill.version() + ")");
                Files.writeString(
                        dir.resolve("SKILL.md"),
                        "---\nname: "
                                + name
                                + "\ndescription: "
                                + description
                                + "\n---\n"
                                + skill.content());
            }
        } catch (IOException e) {
            throw new IllegalStateException("Workspace unavailable", e);
        }
        var builder =
                HarnessAgent.builder()
                        .name("assistant")
                        .sysPrompt(turn.prompt())
                        .workspace(workspace)
                        .conflictPolicy(ConflictPolicy.FAIL)
                        .permissionContext(
                                io.agentscope.core.permission.PermissionContextState.builder()
                                        .mode(permissionMode)
                                        .build())
                        .enableTaskList(false)
                        .enablePlanMode(planning)
                        .disableWebTools()
                        .disableSubagents()
                        .disableDynamicSubagents()
                        .disableDynamicSkills()
                        .disableDefaultWorkspaceSkills()
                        .skillsEnabled(!turn.skills().isEmpty())
                        .disableAtPathExpansion()
                        .disableToolsConfig()
                        .disableMemoryHooks()
                        .disableTranscript()
                        .maxIters(8);
        if (!turn.memoryEnabled()) builder.disableMemoryTools().disableWorkspaceContext();
        var writtenPlan = new AtomicReference<String>();
        builder.hook(
                new io.agentscope.core.hook.Hook() {
                    public <T extends io.agentscope.core.hook.HookEvent>
                            reactor.core.publisher.Mono<T> onEvent(T event) {
                        return reactor.core.publisher.Mono.fromCallable(
                                () -> {
                                    if (event
                                            instanceof
                                            io.agentscope.core.hook.PreActingEvent action) {
                                        String tool = action.getToolUse().getName();
                                        // Remote calls reserve within the registered tool itself.
                                        if (turn.mcp() == null
                                                || !turn.mcp().readOnlyTools().contains(tool))
                                            meter.tool(tool);
                                    }
                                    if (event
                                                    instanceof
                                                    io.agentscope.core.hook.PostActingEvent action
                                            && "plan_write".equals(action.getToolUse().getName())
                                            && action.getToolResult() != null
                                            && action.getToolResult().getState()
                                                    == io.agentscope.core.message.ToolResultState
                                                            .SUCCESS
                                            && action.getToolUse().getInput().get("content")
                                                    instanceof String content)
                                        writtenPlan.set(content);
                                    if (event
                                                    instanceof
                                                    io.agentscope.core.hook.PostActingEvent action
                                            && Set.of("TaskCreate", "TaskUpdate")
                                                    .contains(action.getToolUse().getName())
                                            && action.getAgent()
                                                    instanceof
                                                    io.agentscope.core.ReActAgent agent) {
                                        var tasks =
                                                agent.getAgentState(user, session)
                                                        .getTasksContext()
                                                        .getTasks();
                                        meter.plan(
                                                tasks.stream()
                                                        .map(HarnessRuntime::planItem)
                                                        .toList());
                                    }
                                    return event;
                                });
                    }
                });
        if (turn.sandbox()) builder.stateStore(states).filesystem(sandbox.prepare(turn));
        else
            builder.distributedStore(store)
                    .filesystem(new RemoteFilesystemSpec().isolationScope(IsolationScope.SESSION))
                    .disableShellTool()
                    .disableFilesystemTools();
        var model =
                turn.model().equals("local-echo") ? new LocalModel() : resolveModel(turn.model());
        var metered = new MeteredModel(model, meter);
        var searchOptions = searchOptions(turn.model());
        if (searchOptions != null) {
            builder.generateOptions(searchOptions);
            builder.sysPrompt(
                    turn.prompt()
                            + "\n当前北京时间日期为 "
                            + java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))
                            + "。对天气、新闻等时效性问题，请利用供应商返回的联网资料，标明资料日期和可核实来源；资料不足时请明确说明。");
        }
        builder.model(metered).maxRetries(1).maxContextTokens(32000);
        var toolkit = new io.agentscope.core.tool.Toolkit();
        var mcp = turn.mcp() == null ? null : ReadOnlyMcp.connect(turn.mcp(), toolkit, meter);
        if (mcp != null) builder.toolkit(toolkit);
        var context = RuntimeContext.builder().userId(user).sessionId(session).build();
        HarnessAgent agent;
        try {
            agent = builder.build();
        } catch (RuntimeException failure) {
            if (mcp != null) mcp.close();
            throw failure;
        }
        try {
            if (turn.memoryEnabled() && turn.previousSlot() != null) {
                states.get(user, turn.previousSlot().toString(), "memory_files", MemoryFiles.class)
                        .ifPresent(memory -> memory.restore(agent.getWorkspaceManager(), context));
            }
            agent.getToolkit().registerTool(new PlanTools());
            agent.getToolkit().registerTool(new ReadPlanTool(agent.getWorkspaceManager()));
            agent.getToolkit().registerTool(new DelegateTool(metered, searchOptions));
            if (turn.previousSlot() != null && !turn.sandbox()) {
                var state =
                        states.get(user, session, "agent_state", AgentState.class).orElseThrow();
                String planFile = state.getPlanModeContext().getCurrentPlanFile();
                if ("plans/PLAN.md".equals(planFile)) {
                    var prior =
                            RuntimeContext.builder()
                                    .userId(user)
                                    .sessionId(turn.previousSlot().toString())
                                    .build();
                    var manager = agent.getWorkspaceManager();
                    String plan = manager.readManagedWorkspaceFileUtf8(prior, planFile);
                    if (plan != null && !plan.isEmpty())
                        manager.writeUtf8WorkspaceRelative(context, planFile, plan);
                }
            }
            if (planning) {
                agent.enterPlanMode(context);
                // The authenticated user's mode selection controls when execution may resume.
                agent.getToolkit().removeTool("plan_exit");
            } else if (agent.isPlanModeActive(context)) {
                agent.exitPlanMode(context);
            }
            if (turn.sandbox()) {
                agent.getToolkit().removeTool("edit_file");
                agent.getToolkit().registerTool(new SandboxEditTool());
            }
        } catch (RuntimeException failure) {
            try {
                agent.close();
            } finally {
                if (mcp != null) mcp.close();
            }
            throw failure;
        }
        var run = agent.prepareRun(List.of(new UserMessage(turn.input())), context);
        return new Handle() {
            @Override
            public String execute(Consumer<String> delta) {
                var result = new AtomicReference<String>();
                run.stream()
                        .doOnNext(
                                event -> {
                                    if (event instanceof TextBlockDeltaEvent text
                                            && text.getDelta() != null)
                                        delta.accept(text.getDelta());
                                    if (event instanceof AgentResultEvent end)
                                        result.set(end.getResult().getTextContent());
                                })
                        .blockLast();
                if (result.get() == null
                        || states.get(user, session, "agent_state", AgentState.class).isEmpty())
                    throw new IllegalStateException(
                            "Run ended without a committed reply and state");
                if (turn.memoryEnabled())
                    states.save(
                            user,
                            session,
                            "memory_files",
                            MemoryFiles.capture(agent.getWorkspaceManager(), context));
                if (turn.sandbox()) sandbox.verify(turn.slot());
                String draft = writtenPlan.get();
                if (draft != null && !draft.isBlank() && !result.get().contains(draft))
                    return result.get() + "\n\n### 保存的计划\n\n" + draft;
                return result.get();
            }

            @Override
            public void cancel() {
                run.cancel();
            }

            @Override
            public void close() {
                try {
                    agent.close();
                } finally {
                    if (mcp != null) mcp.close();
                }
            }
        };
    }
}
