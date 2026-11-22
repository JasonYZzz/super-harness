package io.superharness.runtime;

import io.agentscope.core.message.*;
import io.agentscope.core.model.*;

import reactor.core.publisher.Flux;

import java.util.List;

/** Deterministic provider for local development and CI. */
public final class LocalModel implements Model {
    public String getModelName() {
        return "local-echo";
    }

    public Flux<ChatResponse> stream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        String text =
                messages.stream()
                        .filter(m -> m.getRole() == MsgRole.USER)
                        .map(Msg::getTextContent)
                        .reduce((first, last) -> last)
                        .orElse("");
        var response =
                Flux.just(
                        ChatResponse.builder()
                                .content(List.of(TextBlock.builder().text("收到：" + text).build()))
                                .build());
        long delay = Long.getLong("sh.local-model-delay-ms", 0L);
        return delay > 0
                ? response.delayElements(java.time.Duration.ofMillis(Math.min(delay, 10000)))
                : response;
    }
}
