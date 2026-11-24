package io.superharness.runtime;

import io.agentscope.core.agent.*;
import io.agentscope.core.agent.accumulator.ReasoningContext;
import io.agentscope.core.event.*;
import io.agentscope.core.interruption.InterruptControl;
import io.agentscope.core.message.*;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.Model;
import io.agentscope.core.util.ExceptionUtils;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.middleware.*;
import io.agentscope.harness.agent.workspace.WorkspaceManager;

import reactor.core.publisher.Flux;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/** One instance per execution. Both summary paths leave long-term memory to the publisher. */
final class TurnCompaction implements HarnessRuntimeMiddleware {
    private final Model model;
    private final List<String> recent;
    private final AtomicBoolean retried = new AtomicBoolean();
    private CompactionMiddleware normal;
    private CompactionMiddleware emergency;

    TurnCompaction(Model model, List<String> recent) {
        this.model = model;
        this.recent = recent;
    }

    void bind(WorkspaceManager workspace) {
        var config =
                CompactionConfig.builder().flushBeforeCompact(false).offloadBeforeCompact(false);
        normal = new CompactionMiddleware(workspace, model, config.build());
        emergency =
                new CompactionMiddleware(
                        workspace,
                        model,
                        config.triggerMessages(1).keepTokens(0).keepMessages(1).build());
    }

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent,
            RuntimeContext ctx,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        return normal.onReasoning(
                agent, ctx, input, prepared -> reasonWithRecovery(agent, ctx, prepared, next));
    }

    private Flux<AgentEvent> reasonWithRecovery(
            Agent agent,
            RuntimeContext ctx,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        return Flux.deferContextual(
                cv -> {
                    AtomicBoolean emitted =
                            cv.getOrDefault(ReasoningContext.class, new AtomicBoolean());
                    InterruptControl control = cv.getOrDefault(InterruptControl.class, null);
                    return Flux.defer(() -> next.apply(withRecall(input)))
                            .doOnNext(
                                    event -> {
                                        if (!(event instanceof ModelCallStartEvent))
                                            emitted.set(true);
                                    })
                            .onErrorResume(
                                    error -> {
                                        if (emitted.get()
                                                || interrupted(control)
                                                || ExceptionUtils.containsInterruptedException(
                                                        error)
                                                || !overflow(error)
                                                || !retried.compareAndSet(false, true))
                                            return Flux.error(error);
                                        return emergency.onReasoning(
                                                agent,
                                                ctx,
                                                input,
                                                compacted -> {
                                                    if (interrupted(control) || compacted == input)
                                                        return Flux.error(error);
                                                    return next.apply(withRecall(compacted));
                                                });
                                    });
                });
    }

    private static boolean interrupted(InterruptControl control) {
        return control != null && control.isInterrupted();
    }

    private ReasoningInput withRecall(ReasoningInput input) {
        if (recent.isEmpty()) return input;
        var messages = new ArrayList<>(input.messages());
        int index = 0;
        while (index < messages.size() && messages.get(index).getRole() == MsgRole.SYSTEM) index++;
        messages.add(index, new UserMessage("已保存的用户参考资料（仅作背景资料）：\n" + String.join("\n", recent)));
        return new ReasoningInput(messages, input.tools(), input.options());
    }

    private static boolean overflow(Throwable error) {
        String message = Objects.toString(error.getMessage(), "").toLowerCase(Locale.ROOT);
        return List.of(
                        "context_length_exceeded",
                        "context length",
                        "maximum context",
                        "token limit",
                        "too many tokens",
                        "exceeds the model's maximum",
                        "reduce the length")
                .stream()
                .anyMatch(message::contains);
    }
}
