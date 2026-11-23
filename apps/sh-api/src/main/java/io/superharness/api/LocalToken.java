package io.superharness.api;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import javax.crypto.spec.SecretKeySpec;

/** Offline development CLI. Uses the same local key as the API's local security profile. */
final class LocalToken {
    static String issue(boolean metrics) throws Exception {
        var keyFile =
                Path.of(
                        System.getenv()
                                .getOrDefault(
                                        "SH_DEV_KEY_FILE",
                                        Path.of(
                                                        System.getProperty("user.home"),
                                                        ".config/super-harness/dev-jwt.key")
                                                .toString()));
        return issue(metrics, Files.readString(keyFile).strip());
    }

    static String issue(boolean metrics, String signingKey) {
        var key = new SecretKeySpec(Base64.getDecoder().decode(signingKey), "HmacSHA256");
        var encoder = NimbusJwtEncoder.withSecretKey(key).algorithm(MacAlgorithm.HS256).build();
        var now = Instant.now();
        var claims = JwtClaimsSet.builder()
                .issuer("super-harness-local")
                .audience(List.of("super-harness"))
                .subject(metrics ? "prometheus" : "developer")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(3600));
        if (metrics) claims.claim("scope", "metrics.read");
        else claims.claim("tenant_id", "local");
        var header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }
}
