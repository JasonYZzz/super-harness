package io.superharness.runtimespi;

import java.net.URI;
import java.util.*;

public record McpProfile(
        String id,
        String tenant,
        String version,
        URI url,
        List<String> readOnlyTools,
        String bearerEnv) {
    public McpProfile {
        if (id == null
                || tenant == null
                || version == null
                || url == null
                || !"https".equals(url.getScheme())
                || url.getHost() == null
                || url.getUserInfo() != null
                || url.getQuery() != null
                || url.getFragment() != null)
            throw new IllegalArgumentException("Invalid MCP deployment profile");
        readOnlyTools = List.copyOf(readOnlyTools);
        if (readOnlyTools.isEmpty()
                || readOnlyTools.size() > 16
                || readOnlyTools.stream().anyMatch(s -> !s.matches("[a-zA-Z0-9_-]{1,64}")))
            throw new IllegalArgumentException("Invalid MCP tool allowlist");
        if (bearerEnv != null && !bearerEnv.matches("SH_MCP_SECRET_[A-Z0-9_]+"))
            throw new IllegalArgumentException("Invalid credential reference");
    }
}
