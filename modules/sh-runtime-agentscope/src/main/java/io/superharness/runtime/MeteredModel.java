package io.superharness.runtime;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.*;
import io.agentscope.core.util.JsonUtils;
import io.superharness.runtimespi.RuntimeMeter;

import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reserve a conservative byte-based allowance before dispatch. Keep it charged on an unknown
 * outcome.
 */
final class MeteredModel implements Model {
    private final Model delegate;
    private final RuntimeMeter meter;

    MeteredModel(Model delegate, RuntimeMeter meter) {
        this.delegate = delegate;
        this.meter = meter;
    }

    public String getModelName() {
        return delegate.getModelName();
    }

    public int getContextWindowSize() {
        return delegate.getContextWindowSize();
    }

    public boolean supportsNativeStructuredOutput() {
        return delegate.supportsNativeStructuredOutput();
    }

    public boolean supportsNativeStructuredOutputWithTools() {
        return delegate.supportsNativeStructuredOutputWithTools();
    }

    public boolean supportsToolChoiceSpecific() {
        return delegate.supportsToolChoiceSpecific();
    }

    public Flux<ChatResponse> stream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return Flux.defer(
                () -> {
                    int input =
                            JsonUtils.getJsonCodec()
                                    .toJson(List.of(messages, tools == null ? List.of() : tools))
                                    .getBytes(StandardCharsets.UTF_8)
                                    .length;
                    int maxOutput = 2048;
                    boolean search =
                            options != null
                                    && Boolean.TRUE.equals(
                                            options.getAdditionalBodyParams().get("enable_search"));
                    var id = meter.reserve(input, maxOutput, search);
                    var usage = new AtomicReference<ChatUsage>();
                    var bounded =
                            GenerateOptions.mergeOptions(
                                    GenerateOptions.builder().maxTokens(maxOutput).build(),
                                    options);
                    return delegate.stream(messages, tools, bounded)
                            .doOnNext(
                                    chunk -> {
                                        if (chunk.getUsage() != null) usage.set(chunk.getUsage());
                                    })
                            .doOnComplete(
                                    () -> {
                                        var u = usage.get();
                                        if (u != null)
                                            meter.record(
                                                    id, u.getInputTokens(), u.getOutputTokens());
                                    });
                });
    }
}
