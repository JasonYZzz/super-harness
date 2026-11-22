package io.superharness.api;

import io.superharness.domain.Problem;
import io.superharness.persistence.Catalog;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import org.springframework.core.env.Environment;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1")
public class CatalogApi {
    private final Catalog catalog;
    private final Environment env;
    private final ModelPrices prices;
    private final io.superharness.persistence.Knowledge knowledge;
    private final io.superharness.runengine.McpCatalog mcp;

    public CatalogApi(
            Catalog catalog,
            Environment env,
            io.superharness.persistence.Knowledge knowledge,
            io.superharness.runengine.McpCatalog mcp,
            ModelPrices prices) {
        this.catalog = catalog;
        this.env = env;
        this.prices = prices;
        this.knowledge = knowledge;
        this.mcp = mcp;
    }

    public record Agent(@NotBlank @Size(max = 120) String name, @Valid Revision revision) {}

    public record Revision(
            @NotBlank @Size(max = 16000) String prompt,
            @Size(min = 1, max = 120) String model,
            @Pattern(regexp = "CHAT|APPROVED_ARTIFACT") String outputMode,
            Boolean sandbox,
            @Size(max = 4) List<UUID> skills,
            Boolean useMemory,
            String mcpProfile) {}

    public record Conversation(
            @NotNull UUID agentId,
            @NotNull UUID agentRevisionId,
            @Size(min = 1, max = 120) String model) {}

    @GetMapping("/mcp-profiles")
    Object mcpProfiles(@AuthenticationPrincipal Jwt jwt) {
        var principal = Identity.principal(jwt);
        catalog.role(principal);
        return mcp.available(principal.tenant());
    }

    @GetMapping("/agents")
    Object agents(@AuthenticationPrincipal Jwt jwt) {
        return catalog.agents(Identity.principal(jwt));
    }

    @org.springframework.transaction.annotation.Transactional
    @PostMapping("/agents")
    Object create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Agent body) {
        var id = catalog.createAgent(Identity.principal(jwt), body.name());
        if (body.revision() != null) publish(jwt, id, body.revision());
        return Map.of("agentId", id);
    }

    @GetMapping("/agents/{id}")
    Object agent(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return catalog.agent(Identity.principal(jwt), id);
    }

    @org.springframework.transaction.annotation.Transactional
    @PatchMapping("/agents/{id}")
    void rename(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @Valid @RequestBody Agent body) {
        catalog.edit(Identity.principal(jwt), id, body.name(), false);
        if (body.revision() != null) publish(jwt, id, body.revision());
    }

    @DeleteMapping("/agents/{id}")
    void archive(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        catalog.edit(Identity.principal(jwt), id, null, true);
    }

    @PostMapping("/agents/{id}/revisions")
    Object publish(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @Valid @RequestBody Revision body) {
        String model = body.model() == null ? prices.defaultModel : body.model();
        if (!prices.allowed.contains(model)) throw new Problem(400, "MODEL_NOT_ALLOWED");
        if (Boolean.TRUE.equals(body.sandbox())
                && !env.getProperty(
                        "SH_SANDBOX_ENABLED",
                        Boolean.class,
                        !env.getProperty("SH_SANDBOX_IMAGE", "").isBlank()))
            throw new Problem(400, "SANDBOX_UNAVAILABLE");
        var p = Identity.principal(jwt);
        catalog.requireEditor(p);
        var policy = new LinkedHashMap<String, Object>();
        if (prices.prices.containsKey(model)) policy.put("pricing", prices.prices.get(model));
        policy.put("outputMode", body.outputMode() == null ? "CHAT" : body.outputMode());
        policy.put("sandbox", Boolean.TRUE.equals(body.sandbox()));
        policy.put("useMemory", Boolean.TRUE.equals(body.useMemory()));
        policy.put("skills", knowledge.pin(p.tenant(), body.skills()));
        if (body.mcpProfile() != null) policy.put("mcp", mcp.get(p.tenant(), body.mcpProfile()));
        return Map.of(
                "agentRevisionId",
                catalog.publish(
                        Identity.principal(jwt),
                        id,
                        body.prompt(),
                        model,
                        env.getProperty("SH_STORE_PROFILE", "jdbc-mysql"),
                        env.getProperty("SH_STORE_VERSION", Integer.class, 1),
                        policy));
    }

    @GetMapping("/agents/{id}/revisions")
    Object revisions(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return catalog.revisions(Identity.principal(jwt), id);
    }

    public record ConversationName(@NotBlank @Size(max = 160) String name) {}

    public record ModelSelection(@NotBlank @Size(max = 120) String model) {}

    private Map<String, Object> modelSettings(String model) {
        if (!prices.allowed.contains(model)) throw new Problem(400, "MODEL_NOT_ALLOWED");
        var settings = new LinkedHashMap<String, Object>();
        settings.put("model", model);
        if (prices.prices.containsKey(model)) settings.put("pricing", prices.prices.get(model));
        return settings;
    }

    @PutMapping("/conversations/{id}/model")
    void selectModel(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @Valid @RequestBody ModelSelection body) {
        catalog.changeConversation(
                Identity.principal(jwt), id, null, false, modelSettings(body.model()), null);
    }

    public record PermissionSelection(
            @NotNull @Pattern(regexp = "default|accept_edits|explore|bypass|dont_ask|plan")
                    String mode) {}

    @PutMapping("/conversations/{id}/permission")
    void selectPermission(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestBody @Valid PermissionSelection body) {
        catalog.changeConversation(Identity.principal(jwt), id, null, false, null, body.mode());
    }

    @GetMapping("/conversations")
    Object listConversations(@AuthenticationPrincipal Jwt jwt, @RequestParam UUID agentId) {
        return catalog.conversations(Identity.principal(jwt), agentId);
    }

    @PatchMapping("/conversations/{id}")
    void renameConversation(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestBody @Valid ConversationName body) {
        catalog.updateConversation(Identity.principal(jwt), id, body.name(), false);
    }

    @DeleteMapping("/conversations/{id}")
    void archiveConversation(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        catalog.updateConversation(Identity.principal(jwt), id, null, true);
    }

    @PostMapping("/conversations")
    Object createConversation(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Conversation body) {
        return Map.of(
                "conversationId",
                catalog.conversation(
                        Identity.principal(jwt),
                        body.agentId(),
                        body.agentRevisionId(),
                        body.model() == null ? null : modelSettings(body.model())));
    }
}
