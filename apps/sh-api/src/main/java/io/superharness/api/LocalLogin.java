package io.superharness.api;

import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

@RestController
@Profile("local")
class LocalLogin {
    record Credentials(String username, String password) {}

    private final Environment environment;

    LocalLogin(Environment environment) {
        this.environment = environment;
    }

    @io.swagger.v3.oas.annotations.security.SecurityRequirements
    @PostMapping(value = "/api/v1/auth/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> login(@RequestBody Credentials credentials) throws Exception {
        String password = environment.getProperty("SH_LOCAL_PASSWORD", "admin");
        if (!environment.getProperty("SH_LOCAL_USERNAME", "admin").equals(credentials.username())
                || password.isBlank()
                || credentials.password() == null
                || !MessageDigest.isEqual(
                        password.getBytes(StandardCharsets.UTF_8),
                        credentials.password().getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("code", "INVALID_CREDENTIALS"));
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(
                        Map.of(
                                "accessToken",
                                LocalToken.issue(
                                        false, environment.getRequiredProperty("SH_DEV_KEY"))));
    }
}
