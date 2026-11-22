package io.superharness.contract;

import jakarta.validation.constraints.*;

import java.util.UUID;

public record RunInput(
        @NotNull UUID conversationId,
        UUID agentId,
        UUID agentRevisionId,
        @NotNull @jakarta.validation.Valid Input input,
        @jakarta.validation.Valid Options options) {
    public record Options(Boolean interactive, @Min(1) @Max(3600) Integer timeoutSeconds) {}

    public int timeout() {
        return options == null || options.timeoutSeconds() == null
                ? 1200
                : options.timeoutSeconds();
    }

    public record Input(
            @Pattern(regexp = "text") @NotNull String type,
            @NotBlank @Size(max = 32000) String text) {}
}
