package io.superharness.runengine;

import io.superharness.runtimespi.AgentRuntime;

import java.util.UUID;

public record Execution(
        String tenant,
        UUID run,
        String owner,
        long fence,
        int attempt,
        String traceparent,
        String profile,
        int profileVersion,
        boolean publishArtifact,
        AgentRuntime.Turn turn) {}
