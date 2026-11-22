package io.superharness.runtime;

import static org.junit.jupiter.api.Assertions.*;

import io.agentscope.core.state.AgentState;

import org.junit.jupiter.api.Test;

import java.util.*;

class PlanToolsTest {
    @Test
    void preservesDependenciesAcrossUpdatesAndReleasesCompletedTasks() {
        var tools = new PlanTools();
        var state = AgentState.builder().userId("test").sessionId("plan").build();
        tools.create("Research", "Research", Map.of("discard", true), state);
        tools.create("Publish", "Publish", null, state);
        Map<String, Object> changes = new HashMap<>();
        changes.put("discard", null);
        changes.put("priority", "high");
        tools.update(
                "1",
                null,
                null,
                List.of("2", "unknown"),
                null,
                "in_progress",
                "Alice",
                changes,
                state);
        assertEquals(List.of("2"), tools.get("1", state).get("blocks"));
        assertEquals(List.of("1"), tools.get("2", state).get("blocked_by"));
        assertEquals(Map.of("priority", "high"), tools.get("1", state).get("metadata"));
        assertTrue(tools.list(state).contains("Research"));
        tools.update("1", null, null, null, null, "completed", null, null, state);
        assertEquals(List.of(), tools.get("2", state).get("blocked_by"));
        tools.update("1", null, null, List.of("2"), null, null, null, null, state);
        assertEquals(List.of(), tools.get("2", state).get("blocked_by"));
        tools.update("2", null, null, null, null, "deleted", null, null, state);
        assertEquals(List.of(), tools.get("1", state).get("blocks"));
        assertThrows(IllegalArgumentException.class, () -> tools.get("2", state));
    }
}
