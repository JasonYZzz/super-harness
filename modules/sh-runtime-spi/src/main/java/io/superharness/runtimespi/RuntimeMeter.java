package io.superharness.runtimespi;

import java.util.UUID;

public interface RuntimeMeter {
    UUID reserve(int inputUpperBound, int maxOutput);

    default UUID reserve(int inputUpperBound, int maxOutput, boolean webSearchRequested) {
        return reserve(inputUpperBound, maxOutput);
    }

    void record(UUID call, int inputTokens, int outputTokens);

    void tool(String name);

    default java.util.List<String> searchMemory(String query) {
        return java.util.List.of();
    }

    default java.util.List<String> searchSession(String query, int limit) {
        return java.util.List.of();
    }

    default String searchDocuments(String query) { return "[]"; }

    default String legacyFile(String path) {
        return "";
    }

    default boolean publishedSlot(UUID slot) {
        return false;
    }

    default void stageMemory(
            java.util.List<String> facts, String summary, java.util.List<String> legacy) {
        throw new UnsupportedOperationException("No durable memory publisher");
    }

    default void plan(java.util.List<java.util.Map<String, Object>> tasks) {}
}
