package io.superharness.api;

import io.superharness.contract.AguiInput;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

class AguiProjectionTest {
    @Test
    void unsuccessfulResumeReplacesThePlanShownAtTheInterrupt() throws Exception {
        var mapper = new ObjectMapper();
        var run = UUID.randomUUID();
        var request =
                new AguiInput(run, "decision", null, null, null, null, null, null, null, null);
        var committed = List.of(Map.of("subject", "previous turn"));
        var tentative = List.of(Map.of("subject", "pending publication"));
        for (String type : List.of("run.failed", "run.cancelled")) {
            var accepted = new HashMap<>(row(1, 0, "run.accepted", Map.of()));
            accepted.put("input", "update plan");
            var end =
                    row(
                            5,
                            1,
                            type,
                            Map.of(
                                    "code",
                                    "run.failed".equals(type) ? "APPROVAL_REJECTED" : "CANCELLED"));
            var history =
                    List.of(
                            row(2, 1, "plan.updated", Map.of("tasks", committed)),
                            accepted,
                            row(3, 1, "plan.updated", Map.of("tasks", tentative)),
                            end);
            var bytes = new ByteArrayOutputStream();
            var projection =
                    new AguiProjection(
                            request,
                            run,
                            Map.of("toolCallId", "publish"),
                            mapper,
                            bytes,
                            seq -> history,
                            approval -> Map.of());
            projection.start();
            assertThat(projection.accept(end)).isTrue();

            var frames =
                    bytes.toString(StandardCharsets.UTF_8)
                            .lines()
                            .filter(line -> line.startsWith("data: "))
                            .toList();
            var state =
                    frames.stream().filter(line -> line.contains("\"STATE_SNAPSHOT\"")).toList();
            assertThat(state).hasSize(1);
            assertThat(mapper.readTree(state.getFirst().substring(6)).at("/snapshot/tasks"))
                    .isEqualTo(mapper.valueToTree(committed));
            assertThat(mapper.readTree(frames.getLast().substring(6)).path("type").asText())
                    .isEqualTo("RUN_ERROR");
        }
    }

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

    @Test
    void recoveredAttemptRestoresTheLastCommittedPlan() throws Exception {
        var events = new ArrayList<Map<String, Object>>();
        for (int turn = 0; turn < 2; turn++) {
            var id = UUID.randomUUID();
            var accepted = new HashMap<>(row(1, 0, "run.accepted", Map.of()));
            accepted.put("input", "hello");
            var timeline = new ArrayList<Map<String, Object>>();
            timeline.add(accepted);
            timeline.add(row(2, 1, "plan.updated", Map.of("tasks", List.of(turn))));
            if (turn == 1) {
                timeline.add(row(3, 1, "run.recovery_required", Map.of()));
                timeline.add(row(4, 2, "run.claimed", Map.of()));
            }
            timeline.add(row(5, turn + 1, "run.completed", Map.of("text", "final")));
            for (var event : timeline) {
                var copy = new HashMap<>(event);
                copy.put("runId", id.toString());
                events.add(copy);
            }
        }
        var history = AguiHistory.read(events, approval -> Map.of(), new ObjectMapper());
        assertThat(history.tasks()).isEqualTo(List.of(0));
        assertThat(history.messages()).hasSize(4);
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
