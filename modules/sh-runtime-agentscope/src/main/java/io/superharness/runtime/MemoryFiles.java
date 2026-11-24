package io.superharness.runtime;

import io.agentscope.core.state.State;

import java.util.Map;

/** Reader for conversation-private memory snapshots written before platform memory publication. */
public record MemoryFiles(Map<String, String> files) implements State {
    java.util.List<String> importNotes() {
        var notes = new java.util.ArrayList<String>();
        files.forEach(
                (path, text) -> {
                    if (text == null || text.isBlank()) return;
                    // Chunk on Unicode code points so large legacy documents remain manageable.
                    int offset = 0;
                    while (offset < text.length()) {
                        int end = Math.min(text.length(), offset + 1800);
                        if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1)))
                            end--;
                        notes.add(path + "\n" + text.substring(offset, end));
                        offset = end;
                    }
                });
        return notes;
    }
}
