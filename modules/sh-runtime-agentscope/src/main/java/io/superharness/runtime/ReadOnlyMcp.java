package io.superharness.runtime;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.*;
import io.agentscope.core.tool.mcp.*;
import io.superharness.runtimespi.*;

import reactor.core.publisher.Mono;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;

final class ReadOnlyMcp {
    static McpClientWrapper connect(McpProfile profile, Toolkit toolkit, RuntimeMeter meter) {
        var builder =
                McpClientBuilder.create(profile.id())
                        .streamableHttpTransport(profile.url().toString())
                        .customizeStreamableHttpClient(
                                c -> c.followRedirects(HttpClient.Redirect.NEVER))
                        .timeout(Duration.ofSeconds(10))
                        .initializationTimeout(Duration.ofSeconds(10))
                        .propagateMeta(false);
        if (profile.bearerEnv() != null)
            builder.header(
                    "Authorization",
                    "Bearer "
                            + Objects.requireNonNull(
                                    System.getenv(profile.bearerEnv()), "Missing MCP credential"));
        var client = builder.buildSync();
        try {
            client.initialize().block(Duration.ofSeconds(12));
            for (String name : profile.readOnlyTools()) {
                var remote =
                        Objects.requireNonNull(
                                client.getCachedTool(name), "Configured MCP tool missing");
                var tool =
                        new McpTool(
                                name,
                                remote.description(),
                                McpTool.convertMcpSchemaToParameters(
                                        remote.inputSchema(), Set.of()),
                                remote.outputSchema(),
                                client,
                                Map.of(),
                                profile.id(),
                                true) {
                            @Override
                            public Mono<ToolResultBlock> callAsync(ToolCallParam args) {
                                return Mono.defer(
                                                () -> {
                                                    meter.tool(name);
                                                    return super.callAsync(args);
                                                })
                                        .timeout(Duration.ofSeconds(12));
                            }
                        };
                toolkit.registerAgentTool(tool);
            }
            return client;
        } catch (RuntimeException e) {
            client.close();
            throw e;
        }
    }
}
