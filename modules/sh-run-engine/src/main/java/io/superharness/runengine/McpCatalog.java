package io.superharness.runengine;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.superharness.domain.Problem;
import io.superharness.runtimespi.McpProfile;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class McpCatalog {
    private final List<McpProfile> profiles;

    public McpCatalog(Environment env) throws java.io.IOException {
        profiles =
                List.of(
                        new ObjectMapper()
                                .readValue(
                                        env.getProperty("SH_MCP_CATALOG", "[]"),
                                        McpProfile[].class));
    }

    public record Summary(String id, String version, List<String> tools) {}

    public List<Summary> available(String tenant) {
        return profiles.stream()
                .filter(p -> p.tenant().equals(tenant))
                .map(p -> new Summary(p.id(), p.version(), p.readOnlyTools()))
                .toList();
    }

    public McpProfile get(String tenant, String id) {
        return profiles.stream()
                .filter(p -> p.tenant().equals(tenant) && p.id().equals(id))
                .findFirst()
                .orElseThrow(Problem::missing);
    }
}
