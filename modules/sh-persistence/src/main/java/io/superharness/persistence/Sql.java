package io.superharness.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Bind UUIDs in unswapped network byte order, matching UUID_TO_BIN(uuid, 0). */
@Component
public class Sql {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper =
            new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    public Sql(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int update(String sql, Object... parameters) {
        return jdbc.update(sql, bind(parameters));
    }

    public List<Map<String, Object>> rows(String sql, Object... parameters) {
        return jdbc.queryForList(sql, bind(parameters));
    }

    public Optional<Map<String, Object>> first(String sql, Object... parameters) {
        return rows(sql, parameters).stream().findFirst();
    }

    public long count(String sql, Object... parameters) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, bind(parameters)));
    }

    private Object[] bind(Object[] parameters) {
        return Arrays.stream(parameters)
                .map(
                        p ->
                                p instanceof UUID id
                                        ? ByteBuffer.allocate(16)
                                                .putLong(id.getMostSignificantBits())
                                                .putLong(id.getLeastSignificantBits())
                                                .array()
                                        : p)
                .toArray();
    }

    /** Platform DATETIME columns are UTC. Publish an explicit offset to browser clients. */
    public static void utcTimes(Map<String, Object> row, String... fields) {
        for (String field : fields) {
            if (row.get(field) instanceof java.time.LocalDateTime time) {
                row.put(field, time.toInstant(java.time.ZoneOffset.UTC).toString());
            }
        }
    }

    public static UUID uuid(Object value) {
        if (value == null) return null;
        ByteBuffer buffer = ByteBuffer.wrap((byte[]) value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    public String json(Object object) {
        try {
            return mapper.writeValueAsString(object);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Invalid JSON value", ex);
        }
    }

    public JsonNode parse(Object value) {
        try {
            return mapper.readTree(value.toString());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Invalid persisted JSON", ex);
        }
    }

    public Map<String, Object> parseObject(Object value) {
        try {
            return mapper.readValue(
                    value.toString(),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Invalid persisted object", ex);
        }
    }

    public String digest(Object object) {
        try {
            byte[] bytes = json(object).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
