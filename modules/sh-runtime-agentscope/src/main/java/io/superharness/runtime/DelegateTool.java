package io.superharness.runtime;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.*;
import io.agentscope.core.tool.*;

import reactor.core.publisher.Mono;

/** Synchronous leaf delegation. The child sees only the supplied task and shares the run budget. */
public final class DelegateTool {
    private final Model model;
    private final GenerateOptions options;

    DelegateTool(Model model, GenerateOptions options) {
        this.model = model;
        this.options = options;
    }

    @Tool(
            name = "delegate_analysis",
            readOnly = true,
            description =
                    "Ask an isolated analysis agent to review supplied text or reason through a"
                        + " self-contained subtask. It has no workspace or action tools.")
    public Mono<String> analyze(
            @ToolParam(name = "task", description = "Self-contained task and relevant context")
                    String task) {
        return Mono.using(
                () -> {
                    var builder =
                            ReActAgent.builder()
                                    .name("analyst")
                                    .sysPrompt(
                                            "Analyze the supplied task and return your findings to"
                                                + " the parent agent.")
                                    .enableTaskList(false)
                                    .model(model)
                                    .maxIters(1);
                    if (options != null) builder.generateOptions(options);
                    return builder.build();
                },
                child -> child.call(task, RuntimeContext.empty()).map(Msg::getTextContent),
                ReActAgent::close);
    }
}
