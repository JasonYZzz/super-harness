package io.superharness.runtime;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.state.State;
import io.agentscope.harness.agent.workspace.WorkspaceManager;

import java.util.LinkedHashMap;
import java.util.Map;

/** Published alongside the state slot so failed attempts cannot overwrite committed memory. */
public record MemoryFiles(Map<String, String> files) implements State {
    static MemoryFiles capture(WorkspaceManager workspace, RuntimeContext context) {
        Map<String, String> files = new LinkedHashMap<>();
        for (String path : workspace.listMemoryFilePaths(context))
            files.put(path, workspace.readMemoryFileUtf8(context, path));
        return new MemoryFiles(files);
    }

    void restore(WorkspaceManager workspace, RuntimeContext context) {
        files.forEach(
                (path, text) -> {
                    if (!workspace.writeMemoryFileUtf8AndConfirm(context, path, text))
                        throw new IllegalStateException("Failed to restore conversation memory");
                });
    }
}
