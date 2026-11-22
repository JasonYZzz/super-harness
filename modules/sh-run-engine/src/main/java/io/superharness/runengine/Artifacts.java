package io.superharness.runengine;

import io.superharness.domain.*;
import io.superharness.persistence.*;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

@Service
public class Artifacts implements AutoCloseable {
    public record Staged(String key, String sha256, int size) {}

    private final Path root;
    private final String bucket;
    private final S3Client s3;
    private final Sql sql;
    private final Catalog catalog;

    public Artifacts(Sql sql, Catalog catalog, Environment env) {
        this.sql = sql;
        this.catalog = catalog;
        root =
                Path.of(env.getProperty("SH_ARTIFACT_PATH", ".local/artifacts"))
                        .toAbsolutePath()
                        .normalize();
        bucket = env.getProperty("SH_S3_BUCKET", "");
        if (bucket.isBlank()) s3 = null;
        else {
            var b =
                    S3Client.builder()
                            .region(Region.of(env.getProperty("AWS_REGION", "us-east-1")))
                            .forcePathStyle(true);
            var endpoint = env.getProperty("SH_S3_ENDPOINT");
            if (endpoint != null) b.endpointOverride(URI.create(endpoint));
            s3 = b.build();
        }
    }

    static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    public Staged stage(String tenant, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 1024 * 1024) throw new Problem(413, "ARTIFACT_TOO_LARGE");
        String sha = hash(bytes), key = hash(tenant.getBytes(StandardCharsets.UTF_8)) + "/" + sha;
        if (s3 != null)
            s3.putObject(
                    b -> b.bucket(bucket).key(key).contentType("text/plain"),
                    RequestBody.fromBytes(bytes));
        else {
            Path target = root.resolve(key);
            try {
                Files.createDirectories(target.getParent());
                Path tmp = Files.createTempFile(target.getParent(), "stage-", ".tmp");
                try {
                    Files.write(tmp, bytes);
                    Files.move(
                            tmp,
                            target,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    Files.deleteIfExists(tmp);
                }
            } catch (IOException e) {
                throw new IllegalStateException("Artifact storage unavailable", e);
            }
        }
        return new Staged(key, sha, bytes.length);
    }

    public List<Map<String, Object>> list(Principal p, UUID run) {
        catalog.role(p);
        return sql.rows(
                """
                SELECT BIN_TO_UUID(a.id) AS id,file_name AS name,byte_length AS bytes,sha256 FROM artifacts a JOIN agent_runs r
                ON a.run_id=r.id AND a.tenant_id=r.tenant_id WHERE a.tenant_id=? AND r.requested_by=? AND r.id=?
                """,
                p.tenant(),
                p.user(),
                run);
    }

    public byte[] download(Principal p, UUID id) {
        catalog.role(p);
        var row =
                sql.first(
                                """
                                SELECT a.object_key,a.sha256 FROM artifacts a JOIN agent_runs r ON a.run_id=r.id AND a.tenant_id=r.tenant_id
                                WHERE a.tenant_id=? AND a.id=? AND r.requested_by=?
                                """,
                                p.tenant(),
                                id,
                                p.user())
                        .orElseThrow(Problem::missing);
        String key = (String) row.get("object_key");
        if (!key.matches("[a-f0-9]{64}/[a-f0-9]{64}"))
            throw new IllegalStateException("Invalid persisted artifact key");
        try {
            byte[] bytes =
                    s3 == null
                            ? Files.readAllBytes(root.resolve(key))
                            : s3.getObjectAsBytes(b -> b.bucket(bucket).key(key)).asByteArray();
            if (!hash(bytes).equals(row.get("sha256")))
                throw new IllegalStateException("Artifact checksum mismatch");
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Artifact unavailable", e);
        }
    }

    public void close() {
        if (s3 != null) s3.close();
    }
}
