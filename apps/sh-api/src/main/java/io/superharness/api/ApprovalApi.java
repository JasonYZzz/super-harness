package io.superharness.api;

import io.superharness.runengine.*;

import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
class ApprovalApi {
    private final Approvals approvals;
    private final Artifacts artifacts;

    ApprovalApi(Approvals approvals, Artifacts artifacts) {
        this.approvals = approvals;
        this.artifacts = artifacts;
    }

    @GetMapping("/approvals")
    Object pending(@AuthenticationPrincipal Jwt jwt) {
        return approvals.pending(Identity.principal(jwt));
    }

    @GetMapping("/runs/{id}/artifacts")
    Object list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return artifacts.list(Identity.principal(jwt), id);
    }

    @GetMapping("/artifacts/{id}")
    ResponseEntity<byte[]> read(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                .header("Content-Disposition", "attachment; filename=\"response.txt\"")
                .cacheControl(CacheControl.noStore())
                .body(artifacts.download(Identity.principal(jwt), id));
    }
}
