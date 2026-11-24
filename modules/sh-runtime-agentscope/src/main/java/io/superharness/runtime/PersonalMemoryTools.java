package io.superharness.runtime;

import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import io.agentscope.core.tool.*;
import io.agentscope.core.util.JsonUtils;
import io.superharness.runtimespi.AgentRuntime.Turn;
import io.superharness.runtimespi.RuntimeMeter;

import java.util.*;

public final class PersonalMemoryTools {
    private final Turn turn;
    private final RuntimeMeter meter;
    private final Map<String, String> legacyFiles;
    private final Set<String> pending = new LinkedHashSet<>();

    PersonalMemoryTools(Turn turn, RuntimeMeter meter, Map<String, String> legacyFiles) {
        this.turn = turn;
        this.meter = meter;
        this.legacyFiles = legacyFiles;
    }

    @Tool(
            name = "memory_save",
            description =
                    "Save a fact explicitly requested by the user for their future conversations"
                            + " across agents. The fact is published on successful completion or"
                            + " approval.")
    public synchronized String save(
            @ToolParam(name = "content", description = "A concise user fact or preference")
                    String content) {
        if (content == null || content.isBlank() || content.length() > 2000)
            return "Provide 1–2000 characters.";
        if (pending.size() >= 16 && !pending.contains(content.strip()))
            return "Memory limit reached for this turn.";
        pending.add(content.strip());
        return "Saved 1 memory for publication on success.";
    }

    @Tool(
            name = "memory_search",
            readOnly = true,
            description =
                    "Search all personal notes, this agent's summaries and legacy conversation"
                            + " memory for a literal keyword.")
    public synchronized String search(
            @ToolParam(name = "query", description = "Keyword or phrase") String query) {
        if (query == null || query.isBlank() || query.length() > 256)
            return "Provide a keyword up to 256 characters.";
        var hits = new LinkedHashSet<>(meter.searchMemory(query));
        pending.stream()
                .filter(s -> s.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)))
                .forEach(hits::add);
        legacyFiles.values().stream()
                .flatMap(String::lines)
                .filter(
                        line ->
                                line.toLowerCase(Locale.ROOT)
                                        .contains(query.toLowerCase(Locale.ROOT)))
                .limit(12)
                .forEach(hits::add);
        return hits.isEmpty() ? "No matching memories." : String.join("\n", hits);
    }

    @Tool(
            name = "memory_get",
            readOnly = true,
            description =
                    "Read lines from MEMORY.md (recent personal and agent memory) or a legacy daily"
                            + " memory file.")
    public synchronized String get(
            @ToolParam(name = "path", description = "MEMORY.md or memory/YYYY-MM-DD.md")
                    String path,
            @ToolParam(name = "startLine", description = "First line, inclusive") int start,
            @ToolParam(name = "endLine", description = "Last line, inclusive") int end) {
        boolean main = "MEMORY.md".equals(path);
        if (!main && (path == null || !path.matches("memory/[0-9]{4}-[0-9]{2}-[0-9]{2}\\.md")))
            return "Unknown memory path.";
        var notes = new LinkedHashSet<String>();
        if (main) {
            notes.addAll(pending);
            notes.addAll(turn.recall().recent());
        }
        String legacy =
                legacyFiles.containsKey(path) ? legacyFiles.get(path) : meter.legacyFile(path);
        if (!legacy.isBlank()) notes.add(legacy);
        if (start < 1 || end < start) return "Invalid line range.";
        return String.join("\n", notes)
                .lines()
                .skip(start - 1L)
                .limit(Math.min(100L, (long) end - start + 1))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    @Tool(
            name = "session_search",
            readOnly = true,
            description = "Search completed turns in this conversation for a keyword.")
    public String history(
            @ToolParam(name = "query", description = "Keyword or phrase") String query,
            @ToolParam(
                            name = "maxResults",
                            description = "Maximum results (1–10)",
                            required = false)
                    Integer maxResults) {
        if (query == null || query.isBlank() || query.length() > 256)
            return "Provide a keyword up to 256 characters.";
        var hits =
                meter.searchSession(query, Math.clamp(maxResults == null ? 5 : maxResults, 1, 10));
        return hits.isEmpty() ? "No matching turns." : String.join("\n", hits);
    }

    void finish(Model model, List<String> legacy) {
        String summary = null;
        if (pending.isEmpty() && !"local-echo".equals(turn.model())) {
            // Use only the raw current user message. Tool output and recalled notes must never
            // be promoted into personal facts merely because they appeared in model context.
            var prompt =
                    new SystemMessage(
                            "Curate durable user memory for this agent. Merge only explicit, stable"
                                + " facts from the current user message into the previous summary."
                                + " Deduplicate, replace obsolete facts, omit secrets, transient"
                                + " requests and instructions. Return NO_CHANGE if nothing changed;"
                                + " otherwise return SUMMARY followed by a newline and the full"
                                + " concise summary, at most 2000 characters.");
            var data =
                    JsonUtils.getJsonCodec()
                            .toJson(
                                    Map.of(
                                            "previousSummary",
                                            turn.recall().summary(),
                                            "currentUserMessage",
                                            turn.input()));
            String answer = null;
            try {
                answer =
                        model.stream(List.of(prompt, new UserMessage(data)), null, null)
                                .flatMapIterable(ChatResponse::getContent)
                                .ofType(TextBlock.class)
                                .map(TextBlock::getText)
                                .collectList()
                                .map(parts -> String.join("", parts).strip())
                                .block(java.time.Duration.ofSeconds(30));
            } catch (RuntimeException unavailable) {
                org.slf4j.LoggerFactory.getLogger(PersonalMemoryTools.class)
                        .warn(
                                "Optional memory consolidation unavailable ({})",
                                unavailable.getClass().getSimpleName());
            }
            if (answer != null && answer.startsWith("SUMMARY\n")) {
                String value = answer.substring(8).strip();
                if (!value.isEmpty()
                        && value.length() <= 2000
                        && !value.equals(turn.recall().summary())) summary = value;
            }
        }
        if (!pending.isEmpty() || summary != null || !legacy.isEmpty())
            meter.stageMemory(List.copyOf(pending), summary, legacy);
    }
}
