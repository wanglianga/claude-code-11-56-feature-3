package com.windfarm.faultref.repo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shared JDBC conversion helpers.
 *
 * JSONB values are bound as plain strings and cast with an explicit
 * {@code ::jsonb} in SQL; this keeps the compiler classpath free of the
 * PostgreSQL driver while still writing real jsonb columns.
 */
public final class Sql {

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private Sql() {
    }

    public static String jsonb(Object value) {
        try {
            return value == null ? "{}" : MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise jsonb value", e);
        }
    }

    private static String text(Object raw) {
        if (raw == null) {
            return null;
        }
        // org.postgresql.util.PGobject (runtime-only) exposes getValue(); read reflectively.
        if (raw.getClass().getName().equals("org.postgresql.util.PGobject")) {
            try {
                Object v = raw.getClass().getMethod("getValue").invoke(raw);
                return v == null ? null : v.toString();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot read PGobject value", e);
            }
        }
        return raw.toString();
    }

    public static Map<String, Object> readMap(Object raw) {
        String t = text(raw);
        if (t == null || t.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return MAPPER.readValue(t, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot parse jsonb column", e);
        }
    }

    public static JsonNode readNode(Object raw) {
        String t = text(raw);
        if (t == null || t.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(t);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot parse jsonb column", e);
        }
    }

    public static UUID uuid(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof UUID u) {
            return u;
        }
        return UUID.fromString(raw.toString());
    }

    public static Instant instant(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Instant i) {
            return i;
        }
        if (raw instanceof OffsetDateTime odt) {
            return odt.toInstant();
        }
        if (raw instanceof java.sql.Timestamp ts) {
            return ts.toInstant();
        }
        return OffsetDateTime.parse(raw.toString()).toInstant();
    }

    /** Extract the UTC offset datetime that pgjdbc binds for timestamptz. */
    public static OffsetDateTime odt(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    public static BigDecimal decimal(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof BigDecimal d) {
            return d;
        }
        return new BigDecimal(raw.toString());
    }

    public static Long nullableLong(Object raw) {
        return raw == null ? null : ((Number) raw).longValue();
    }

    public static UUID[] uuidArray(List<UUID> ids) {
        return ids == null || ids.isEmpty() ? null : ids.toArray(new UUID[0]);
    }

    public static JsonNode mapNode(Object value) {
        return MAPPER.valueToTree(value == null ? Map.of() : value);
    }
}
