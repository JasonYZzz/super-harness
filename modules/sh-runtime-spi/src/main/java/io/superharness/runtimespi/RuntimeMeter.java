package io.superharness.runtimespi;

import java.util.UUID;

public interface RuntimeMeter {
    UUID reserve(int inputUpperBound, int maxOutput);

    default UUID reserve(int inputUpperBound, int maxOutput, boolean webSearchRequested) {
        return reserve(inputUpperBound, maxOutput);
    }

    void record(UUID call, int inputTokens, int outputTokens);

    void tool(String name);

    default void plan(java.util.List<java.util.Map<String, Object>> tasks) {}
}
