package io.superharness.api;

import io.superharness.runengine.Runs;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1")
class RunApi {
    private final Runs runs;

    RunApi(Runs runs) {
        this.runs = runs;
    }

    @GetMapping("/runs")
    Object list(@AuthenticationPrincipal Jwt jwt) {
        return runs.recent(Identity.principal(jwt));
    }

    @GetMapping("/runs/{id}")
    Object status(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return runs.status(Identity.principal(jwt), id);
    }

    @GetMapping("/runs/{id}/trace")
    Object trace(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return runs.trace(Identity.principal(jwt), id);
    }
}
