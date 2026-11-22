package io.superharness.api;

import io.superharness.persistence.TenantSettings;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/tenant")
class TenantApi {
    private final TenantSettings tenants;
    private final ModelPrices models;

    TenantApi(TenantSettings tenants, ModelPrices models) {
        this.tenants = tenants;
        this.models = models;
    }

    record Member(
            @NotBlank @Size(max = 120) String subject,
            @NotNull @Pattern(regexp = "OWNER|APPROVER|EDITOR|MEMBER") String role,
            boolean active) {}

    record Quota(
            @Min(1) @Max(10000) int maxOpenRuns,
            @Min(1) @Max(1000000000000L) long dailyTokens,
            @Min(1) @Max(100000000) int runTokens,
            @Min(0) @Max(1024) int runTools) {}

    @GetMapping
    Map<String, Object> settings(@AuthenticationPrincipal Jwt jwt) {
        var result = tenants.settings(Identity.principal(jwt));
        result.put("models", models.allowed);
        result.put("defaultModel", models.defaultModel);
        return result;
    }

    @GetMapping("/members")
    Object members(@AuthenticationPrincipal Jwt jwt) {
        return tenants.members(Identity.principal(jwt));
    }

    @GetMapping("/audit")
    Object audit(@AuthenticationPrincipal Jwt jwt) {
        return tenants.audit(Identity.principal(jwt));
    }

    @PutMapping("/members")
    void member(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Member m) {
        tenants.member(Identity.principal(jwt), m.subject(), m.role(), m.active());
    }

    @PutMapping("/quota")
    void quota(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Quota q) {
        tenants.limits(
                Identity.principal(jwt),
                q.maxOpenRuns(),
                q.dailyTokens(),
                q.runTokens(),
                q.runTools());
    }
}
