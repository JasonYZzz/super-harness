package io.superharness.api;

import io.superharness.domain.*;

import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;

import java.util.Base64;

import javax.crypto.spec.SecretKeySpec;

@Configuration
public class Identity {
    static Principal principal(Jwt jwt) {
        try {
            return new Principal(jwt.getClaimAsString("tenant_id"), jwt.getSubject());
        } catch (IllegalArgumentException e) {
            throw Problem.denied();
        }
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http, Environment env) throws Exception {
        return http.csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        a -> {
                            a.requestMatchers(
                                            org.springframework.http.HttpMethod.GET,
                                            "/api/v1/auth/mode")
                                    .permitAll();
                            if (env.matchesProfiles("local"))
                                a.requestMatchers(
                                                org.springframework.http.HttpMethod.POST,
                                                "/api/v1/auth/login")
                                        .permitAll();
                            a.requestMatchers(
                                            "/",
                                            "/index.html",
                                            "/chat",
                                            "/chat/**",
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
                                            "/workspace/memory",
                                            "/agentscope.svg",
                                            "/LICENSE-agentscope.txt",
                                            "/assets/**",
                                            "/actuator/health",
                                            "/actuator/health/**",
                                            "/error")
                                    .permitAll()
                                    .requestMatchers("/actuator/prometheus")
                                    .hasAuthority("SCOPE_metrics.read")
                                    .requestMatchers(
                                            "/api/v1/**", "/v3/api-docs", "/v3/api-docs.yaml")
                                    .authenticated()
                                    .anyRequest()
                                    .denyAll();
                        })
                .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()))
                .build();
    }

    @Bean
    @Profile("local")
    JwtDecoder localDecoder(Environment env) {
        var key =
                new SecretKeySpec(
                        Base64.getDecoder().decode(env.getRequiredProperty("SH_DEV_KEY")),
                        "HmacSHA256");
        var decoder = NimbusJwtDecoder.withSecretKey(key).build();
        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(
                        JwtValidators.createDefaultWithIssuer("super-harness-local"),
                        new JwtClaimValidator<java.util.List<String>>(
                                "aud", aud -> aud != null && aud.contains("super-harness"))));
        return decoder;
    }

    @Bean
    @Profile("!local")
    JwtDecoder oidc(Environment env) {
        String issuer = env.getRequiredProperty("SH_OIDC_ISSUER");
        NimbusJwtDecoder decoder = JwtDecoders.fromIssuerLocation(issuer);
        String audience = env.getRequiredProperty("SH_OIDC_AUDIENCE");
        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(
                        JwtValidators.createDefaultWithIssuer(issuer),
                        new JwtClaimValidator<java.util.List<String>>(
                                "aud", a -> a != null && a.contains(audience))));
        return decoder;
    }
}
