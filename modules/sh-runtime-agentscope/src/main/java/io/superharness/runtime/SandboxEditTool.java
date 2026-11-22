package io.superharness.runtime;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.harness.agent.sandbox.SandboxAcquireResult;
import io.agentscope.harness.agent.workspace.WorkspacePathNormalizer;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class SandboxEditTool {
    private static String argument(String text) {
        return "'"
                + Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8))
                + "'";
    }

    @Tool(
            name = "edit_file",
            description =
                    "Perform exact string replacement in a workspace file. Text must be unique"
                        + " unless replace_all is true.")
    public String edit(
            RuntimeContext ctx,
            @ToolParam(name = "path", description = "File to edit") String path,
            @ToolParam(name = "old_string", description = "Text to find") String oldText,
            @ToolParam(name = "new_string", description = "Replacement text") String newText,
            @ToolParam(
                            name = "replace_all",
                            description = "Replace all occurrences",
                            required = false)
                    Boolean all)
            throws Exception {
        var sandbox = ctx.get(SandboxAcquireResult.class).getSandbox();
        var normalized = ctx.get(WorkspacePathNormalizer.class).normalize(path, ctx);
        String command =
                "java -Xmx64m -XX:-UsePerfData -cp /opt/tools io.superharness.runtime.SandboxEdit "
                        + argument(normalized)
                        + " "
                        + argument(oldText)
                        + " "
                        + argument(newText)
                        + " "
                        + Boolean.TRUE.equals(all);
        var result = sandbox.exec(ctx, command, 30);
        return (result.exitCode() == 0 ? "" : "Error: ") + result.combinedOutput().strip();
    }
}
