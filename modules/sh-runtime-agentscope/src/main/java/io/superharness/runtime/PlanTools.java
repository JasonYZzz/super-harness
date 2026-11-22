package io.superharness.runtime;

import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.Task;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.math.BigInteger;
import java.util.*;
import java.util.stream.Collectors;

/** The Web UI's per-task operations on the SDK's conversation state. */
public final class PlanTools {
    @Tool(
            name = "TaskCreate",
            description =
                    "Add a pending task to this session's plan. Use TaskUpdate for ownership,"
                            + " dependencies and progress.",
            stateInjected = true,
            concurrencySafe = false)
    public String create(
            @ToolParam(name = "subject", description = "Brief task title") String subject,
            @ToolParam(name = "description", description = "What needs to be done")
                    String description,
            @ToolParam(
                            name = "metadata",
                            description = "Optional structured task metadata",
                            required = false)
                    Map<String, Object> metadata,
            AgentState state) {
        var tasks = state.getTasksContext().tasksMutable();
        var next =
                tasks.stream()
                        .map(Task::getId)
                        .filter(id -> id.matches("[0-9]+"))
                        .map(BigInteger::new)
                        .max(Comparator.naturalOrder())
                        .orElse(BigInteger.ZERO)
                        .add(BigInteger.ONE)
                        .toString();
        tasks.add(
                Task.builder()
                        .id(next)
                        .subject(subject)
                        .description(description)
                        .metadata(metadata == null ? Map.of() : metadata)
                        .state(Task.State.PENDING)
                        .build());
        return "Task (id=" + next + ") created successfully: " + subject;
    }

    @Tool(
            name = "TaskList",
            description = "List this session's tasks with status, owner and prerequisites.",
            stateInjected = true,
            readOnly = true)
    public String list(AgentState state) {
        var tasks = state.getTasksContext().getTasks();
        if (tasks.isEmpty()) return "No tasks available.";
        return tasks.stream()
                .map(
                        t ->
                                t.getId()
                                        + " ["
                                        + t.getState().getWire()
                                        + "] "
                                        + t.getSubject()
                                        + (t.getOwner() == null ? "" : " (" + t.getOwner() + ")")
                                        + (t.getBlockedBy().isEmpty()
                                                ? ""
                                                : " [blocked by "
                                                        + String.join(", ", t.getBlockedBy())
                                                        + "]"))
                .collect(Collectors.joining("\n"));
    }

    @Tool(
            name = "TaskGet",
            description = "Read the complete task, including its dependencies and metadata.",
            stateInjected = true,
            readOnly = true)
    public Map<String, Object> get(
            @ToolParam(name = "task_id", description = "Task identifier") String id,
            AgentState state) {
        return HarnessRuntime.planItem(find(state.getTasksContext().getTasks(), id));
    }

    private static Task find(List<Task> tasks, String id) {
        return tasks.stream()
                .filter(task -> task.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + id));
    }

    private static Task.Builder copy(Task t) {
        return Task.builder()
                .id(t.getId())
                .createdAt(t.getCreatedAt())
                .subject(t.getSubject())
                .description(t.getDescription())
                .metadata(t.getMetadata())
                .state(t.getState())
                .owner(t.getOwner())
                .blocks(t.getBlocks())
                .blockedBy(t.getBlockedBy());
    }

    @Tool(
            name = "TaskUpdate",
            description =
                    "Change task fields, merge metadata, add dependencies or update status."
                            + " Complete a task to release dependent tasks.",
            stateInjected = true,
            concurrencySafe = false)
    public String update(
            @ToolParam(name = "task_id", description = "Task identifier") String id,
            @ToolParam(name = "subject", description = "New title", required = false)
                    String subject,
            @ToolParam(name = "description", description = "New description", required = false)
                    String description,
            @ToolParam(name = "add_blocks", description = "IDs this task blocks", required = false)
                    List<String> blocks,
            @ToolParam(
                            name = "add_blocked_by",
                            description = "IDs that block this task",
                            required = false)
                    List<String> blockedBy,
            @ToolParam(
                            name = "status",
                            description = "pending, in_progress, completed or deleted",
                            required = false)
                    String status,
            @ToolParam(name = "owner", description = "New owner", required = false) String owner,
            @ToolParam(
                            name = "metadata",
                            description = "Metadata changes. A null value removes a key.",
                            required = false)
                    Map<String, Object> changes,
            AgentState state) {
        var tasks = state.getTasksContext().tasksMutable();
        Task current = find(tasks, id);
        boolean delete = "deleted".equals(status);
        var builder = copy(current);
        if (status != null && !delete) builder.state(Task.State.fromWire(status));
        if (subject != null && !subject.isEmpty()) builder.subject(subject);
        if (description != null) builder.description(description);
        if (owner != null) builder.owner(owner);
        var metadata = new LinkedHashMap<String, Object>();
        if (current.getMetadata() != null) metadata.putAll(current.getMetadata());
        if (changes != null)
            changes.forEach(
                    (key, value) -> {
                        if (value == null) metadata.remove(key);
                        else metadata.put(key, value);
                    });
        var outgoing = new LinkedHashSet<>(current.getBlocks());
        var incoming = new LinkedHashSet<>(current.getBlockedBy());
        var ids = tasks.stream().map(Task::getId).collect(Collectors.toSet());
        if (blocks != null) blocks.stream().filter(ids::contains).forEach(outgoing::add);
        if (blockedBy != null) blockedBy.stream().filter(ids::contains).forEach(incoming::add);
        tasks.set(
                tasks.indexOf(current),
                builder.metadata(metadata)
                        .blocks(List.copyOf(outgoing))
                        .blockedBy(List.copyOf(incoming))
                        .build());
        tasks.replaceAll(
                t -> {
                    var b = new LinkedHashSet<>(t.getBlocks());
                    var before = new LinkedHashSet<>(t.getBlockedBy());
                    if (delete) {
                        b.remove(id);
                        before.remove(id);
                    } else {
                        if (incoming.contains(t.getId())
                                && !current.getBlockedBy().contains(t.getId())) b.add(id);
                        if (outgoing.contains(t.getId())
                                && !current.getBlocks().contains(t.getId())) before.add(id);
                        if ("completed".equals(status)) before.remove(id);
                    }
                    return copy(t).blocks(List.copyOf(b)).blockedBy(List.copyOf(before)).build();
                });
        if (delete) tasks.removeIf(t -> t.getId().equals(id));
        return "Task (id=" + id + ") " + (delete ? "deleted." : "updated.");
    }
}
