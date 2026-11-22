package io.superharness.api;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import javax.crypto.Mac;
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

    static String issue(boolean metrics, String signingKey) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(Base64.getDecoder().decode(signingKey), mac.getAlgorithm()));
        var json = new ObjectMapper();
        var encoder = Base64.getUrlEncoder().withoutPadding();
        long now = Instant.now().getEpochSecond();
        var claims =
                Map.of(
                        "iss",
                        "super-harness-local",
                        "aud",
                        List.of("super-harness"),
                        "sub",
                        "developer",
                        "tenant_id",
                        "local",
                        "scope",
                        metrics ? "metrics.read" : "",
                        "iat",
                        now,
                        "exp",
                        now + 3600);
        String body =
                encoder.encodeToString(json.writeValueAsBytes(Map.of("alg", "HS256", "typ", "JWT")))
                        + "."
                        + encoder.encodeToString(json.writeValueAsBytes(claims));
        return body
                + "."
                + encoder.encodeToString(mac.doFinal(body.getBytes(StandardCharsets.US_ASCII)));
    }
}
