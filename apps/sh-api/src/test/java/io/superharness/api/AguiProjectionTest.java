package io.superharness.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

class AguiProjectionTest {
    @Test
    void replaySeparatesAttemptsAndCountsEachModelCallOnce() throws Exception {
        var mapper = new ObjectMapper();
        var bytes = new ByteArrayOutputStream();
        var id = UUID.randomUUID();
        var request =
                new AguiInput(id, "interaction", null, null, null, null, null, null, null, null);
        var projection =
                new AguiProjection(request, id, null, mapper, bytes, s -> List.of(), a -> Map.of());
        var usage = Map.<String, Object>of("callId", "first", "input", 3, "output", 5);
        assertThat(projection.accept(row(1, 0, "run.accepted", Map.of()))).isFalse();
        projection.accept(row(2, 1, "text.delta", Map.of("text", "first draft")));
        projection.accept(row(3, 1, "usage.updated", usage));
        projection.accept(row(4, 1, "usage.updated", usage));
        projection.accept(
                row(5, 2, "usage.updated", Map.of("callId", "next", "input", 2, "output", 4)));
        assertThat(projection.accept(row(6, 2, "run.completed", Map.of("text", "final only"))))
                .isTrue();

        var frames = new ArrayList<JsonNode>();
        for (String line :
                bytes.toString(StandardCharsets.UTF_8)
                        .lines()
                        .filter(l -> l.startsWith("data: "))
                        .toList()) {
            frames.add(mapper.readTree(line.substring(6)));
        }
        assertThat(frames.stream().map(f -> f.path("type").asText()))
                .containsExactly(
                        "RUN_STARTED",
                        "CUSTOM",
                        "TEXT_MESSAGE_START",
                        "TEXT_MESSAGE_CONTENT",
                        "CUSTOM",
                        "TEXT_MESSAGE_END",
                        "CUSTOM",
                        "TEXT_MESSAGE_START",
                        "TEXT_MESSAGE_CONTENT",
                        "TEXT_MESSAGE_END",
                        "RUN_FINISHED");
        assertThat(frames.get(5).path("messageId").asText()).isEqualTo(id + "-1");
        assertThat(frames.get(6).at("/value/cumulative/totalTokens").asLong()).isEqualTo(14);
        assertThat(frames.get(8).path("messageId").asText()).isEqualTo(id + "-2");
        assertThat(frames.get(8).path("delta").asText()).isEqualTo("final only");
    }

    private static Map<String, Object> row(
            int seq, int attempt, String type, Map<String, Object> payload) {
        return Map.of(
                "type",
                type,
                "seq",
                seq,
                "attempt",
                attempt,
                "createdAt",
                "2026-11-22T00:00:00Z",
                "payload",
                payload);
    }
}
