package io.superharness.runtime;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.*;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.superharness.runtimespi.RuntimeMeter;
import java.util.UUID;
import java.util.regex.Pattern;

public final class ToolOutputReader {
    private static final Pattern PATH =
            Pattern.compile(
                    "large_tool_results/([a-f0-9-]{36})/assistant/[a-zA-Z0-9_-]+-[a-f0-9]{16}");
    private final AbstractFilesystem files;
    private final RuntimeMeter meter;

    ToolOutputReader(AbstractFilesystem files, RuntimeMeter meter) {
        this.files = files;
        this.meter = meter;
    }

    @Tool(
            name = "read_file",
            readOnly = true,
            description =
                    "Read a page of an evicted tool output; increase the character offset to"
                        + " continue.")
    public String read(
            RuntimeContext ctx,
            @ToolParam(name = "path", description = "Exact large_tool_results path in tool output")
                    String path,
            @ToolParam(
                            name = "offset",
                            description = "Character offset, starting at zero",
                            required = false)
                    Integer offset) {
        var match = PATH.matcher(path == null ? "" : path);
        if (!match.matches()) return "Unknown tool output.";
        String source = match.group(1);
        UUID slot;
        try {
            slot = UUID.fromString(source);
        } catch (IllegalArgumentException e) {
            return "Unknown tool output.";
        }
        if (!source.equals(ctx.getSessionId()) && !meter.publishedSlot(slot))
            return "Unknown tool output.";
        var origin = RuntimeContext.builder().userId(ctx.getUserId()).sessionId(source).build();
        var read = files.read(origin, path, 0, 0);
        if (!read.isSuccess()) return "Tool output unavailable.";
        String content = read.fileData().content();
        int start = offset == null ? 0 : offset;
        if (start < 0 || start > content.length()) return "Invalid offset.";
        int end = (int) Math.min((long) start + 4096, content.length());
        return content.substring(start, end)
                + (end == content.length() ? "" : "\n[Next offset: " + end + "]");
    }
}
