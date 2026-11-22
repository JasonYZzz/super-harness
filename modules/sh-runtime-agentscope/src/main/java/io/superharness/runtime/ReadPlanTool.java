package io.superharness.runtime;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.harness.agent.workspace.WorkspaceManager;

/** Allows a reviewed draft to be read even when generic filesystem tools are disabled. */
public final class ReadPlanTool {
    private final WorkspaceManager workspace;

    ReadPlanTool(WorkspaceManager workspace) {
        this.workspace = workspace;
    }

    @Tool(
            name = "read_plan",
            description = "Read the saved plan for the current conversation.",
            readOnly = true)
    public String read(RuntimeContext context) {
        String draft = workspace.readManagedWorkspaceFileUtf8(context, "plans/PLAN.md");
        return draft.isEmpty() ? "No saved plan." : draft;
    }
}
