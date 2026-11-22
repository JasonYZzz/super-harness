package io.superharness.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class ConsoleRoutes {
    @GetMapping({
        "/chat",
        "/chat/**",
        "/ag-ui/*",
        "/setup",
        "/schedule",
        "/channel",
        "/credential",
        "/mcp",
        "/mcp/*",
        "/skill",
        "/skill/*",
        "/knowledge",
        "/knowledge/*",
        "/voice-profile",
        "/workspace/skills",
        "/workspace/memory"
    })
    String index() {
        return "forward:/index.html";
    }
}
