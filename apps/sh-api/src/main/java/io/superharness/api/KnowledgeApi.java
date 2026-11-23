package io.superharness.api;

import io.agentscope.core.skill.util.MarkdownSkillParser;
import io.superharness.domain.Problem;
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
            @Size(max = 120) String name,
            @NotBlank @Size(max = 80) String version,
            @NotBlank @Size(max = 8000) String content,
            @Size(max = 1024) String description,
            @Size(max = 16) Map<@NotNull @Size(max = 240) String, @NotNull @Size(max = 65536) String> resources) {}

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
        String name = s.name();
        String description = s.description();
        var parsed = MarkdownSkillParser.parse(s.content());
        if (parsed.hasFrontmatter()) {
            var metadata = parsed.getMetadata();
            if (!(metadata.get("name") instanceof String declaredName)
                    || !(metadata.get("description") instanceof String purpose) || purpose.isBlank()
                    || parsed.getContent().isBlank()
                    || (name != null && !name.isBlank() && !name.equals(declaredName)))
                throw new Problem(400, "INVALID_SKILL_DOCUMENT");
            name = declaredName;
            description = purpose;
        }
        if (name == null || name.isBlank() || name.length() > 120)
            throw new Problem(400, "INVALID_SKILL_NAME");
        if (description == null || description.isBlank()) description = name + " (" + s.version() + ")";
        if (description.length() > 1024)
            throw new Problem(400, "INVALID_SKILL_DESCRIPTION");
        return Map.of("id", knowledge.publish(Identity.principal(jwt), name, s.version(),
                s.content(), description, s.resources() == null ? Map.of() : s.resources()));
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
