package io.superharness.api;

import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
class AuthMode {
    private final Environment environment;

    AuthMode(Environment environment) {
        this.environment = environment;
    }

    @io.swagger.v3.oas.annotations.security.SecurityRequirements
    @GetMapping("/api/v1/auth/mode")
    Map<String, Boolean> mode() {
        return Map.of("localLogin", environment.matchesProfiles("local"));
    }
}
