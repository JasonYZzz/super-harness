package io.superharness.api;

import io.superharness.persistence.Knowledge;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1")
class KnowledgeApi {
    private final Knowledge knowledge;

    KnowledgeApi(Knowledge knowledge) {
        this.knowledge = knowledge;
    }

    record Skill(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Size(max = 80) String version,
            @NotBlank @Size(max = 8000) String content) {}

    record Memory(@NotBlank @Size(max = 2000) String text) {}

    @GetMapping("/skills")
    Object skills(@AuthenticationPrincipal Jwt jwt) {
        return knowledge.skills(Identity.principal(jwt));
    }

    @GetMapping("/skills/{id}")
    Object skill(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return knowledge.skill(Identity.principal(jwt), id);
    }

    @PostMapping("/skills")
    Object publish(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Skill s) {
        return Map.of(
                "id",
                knowledge.publish(Identity.principal(jwt), s.name(), s.version(), s.content()));
    }

    @DeleteMapping("/skills/{id}")
    void revoke(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        knowledge.revoke(Identity.principal(jwt), id);
    }

    @GetMapping("/memory")
    Object list(@AuthenticationPrincipal Jwt jwt) {
        return knowledge.memories(Identity.principal(jwt));
    }

    @PostMapping("/memory")
    Object remember(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Memory m) {
        return Map.of("id", knowledge.remember(Identity.principal(jwt), m.text()));
    }

    @DeleteMapping("/memory/{id}")
    void forget(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        knowledge.forget(Identity.principal(jwt), id);
    }
}
