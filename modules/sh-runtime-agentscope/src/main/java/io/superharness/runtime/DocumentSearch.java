package io.superharness.runtime;

import io.agentscope.core.tool.*;
import io.superharness.runtimespi.RuntimeMeter;

public record DocumentSearch(RuntimeMeter meter) {
    @Tool(
            name = "knowledge_search",
            readOnly = true,
            description =
                    "Search the document library selected for this conversation for a literal"
                        + " keyword or phrase. Results include document names and matching"
                        + " excerpts.")
    public String search(
            @ToolParam(name = "query", description = "A literal word or phrase from the document")
                    String query) {
        if (query == null || query.isBlank() || query.length() > 256)
            return "Provide a keyword up to 256 characters.";
        return meter.searchDocuments(query.strip());
    }
}
