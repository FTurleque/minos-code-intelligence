package com.minos.storage.postgresql;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Validates the PostgreSQL JDBC endpoint before credentials are ever handed to the driver. */
final class PostgresJdbcUrlPolicy {
    private static final String MANAGED_DOCKER_HOST = "minos-postgres";
    private static final String SSL_MODE_PARAMETER = "sslmode";
    private static final Set<String> ALLOWED_URL_PARAMETERS = Set.of("sslmode");
    private static final Set<String> ALLOWED_SSL_MODES = Set.of(
            "disable", "allow", "prefer", "require", "verify-ca", "verify-full");

    private PostgresJdbcUrlPolicy() {
    }

    static void validate(String value, boolean managed) throws IOException {
        URI uri = parseJdbcUri(value);
        validateJdbcUriShape(uri);
        Map<String, String> query = queryParameters(uri.getRawQuery());
        validateJdbcParameters(query);
        validateJdbcHostPolicy(uri.getHost(), query.get(SSL_MODE_PARAMETER), managed);
    }

    private static URI parseJdbcUri(String value) throws IOException {
        if (!value.startsWith("jdbc:postgresql://")) {
            throw new IOException("MINOS_POSTGRES_URL must use jdbc:postgresql://");
        }
        try {
            return new URI(value.substring("jdbc:".length()));
        } catch (URISyntaxException exception) {
            throw new IOException("MINOS_POSTGRES_URL is invalid", exception);
        }
    }

    private static void validateJdbcUriShape(URI uri) throws IOException {
        if (uri.getUserInfo() != null) {
            throw new IOException("MINOS_POSTGRES_URL must not contain user-info credentials");
        }
        if (uri.getRawFragment() != null) {
            throw new IOException("MINOS_POSTGRES_URL must not contain a fragment");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IOException("MINOS_POSTGRES_URL must contain a host");
        }
        String database = uri.getRawPath();
        if (database == null || database.isBlank() || "/".equals(database)) {
            throw new IOException("MINOS_POSTGRES_URL must contain a database name");
        }
    }

    private static void validateJdbcParameters(Map<String, String> query) throws IOException {
        for (String key : query.keySet()) {
            if (!ALLOWED_URL_PARAMETERS.contains(key)) {
                throw new IOException("MINOS_POSTGRES_URL contains unsupported parameter: " + key);
            }
        }
        String sslMode = query.get(SSL_MODE_PARAMETER);
        if (sslMode != null && !ALLOWED_SSL_MODES.contains(sslMode.toLowerCase(Locale.ROOT))) {
            throw new IOException("MINOS_POSTGRES_URL contains an unsupported sslmode");
        }
    }

    private static void validateJdbcHostPolicy(String host, String sslMode, boolean managed) throws IOException {
        if (managed) {
            if (!MANAGED_DOCKER_HOST.equalsIgnoreCase(host) && !loopbackHost(host)) {
                throw new IOException("managed PostgreSQL must use the MINOS Docker service or loopback");
            }
            return;
        }
        if (loopbackHost(host)) return;
        if (!"verify-full".equalsIgnoreCase(sslMode)) {
            throw new IOException("external PostgreSQL requires sslmode=verify-full");
        }
    }

    private static Map<String, String> queryParameters(String rawQuery) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) return values;
        for (String pair : rawQuery.split("&", -1)) {
            if (pair.isEmpty()) {
                throw new IOException("MINOS_POSTGRES_URL contains an empty query parameter name");
            }
            int separator = pair.indexOf('=');
            String rawKey = separator < 0 ? pair : pair.substring(0, separator);
            String rawValue = separator < 0 ? "" : pair.substring(separator + 1);
            try {
                String key = URLDecoder.decode(rawKey, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                String decoded = URLDecoder.decode(rawValue, StandardCharsets.UTF_8);
                if (key.isEmpty()) {
                    throw new IOException("MINOS_POSTGRES_URL contains an empty query parameter name");
                }
                if (values.putIfAbsent(key, decoded) != null) {
                    throw new IOException("MINOS_POSTGRES_URL contains duplicate parameter: " + key);
                }
            } catch (IllegalArgumentException exception) {
                throw new IOException("MINOS_POSTGRES_URL contains invalid query encoding", exception);
            }
        }
        return values;
    }

    private static boolean loopbackHost(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        if ("localhost".equals(normalized)
                || "::1".equals(normalized)
                || "0:0:0:0:0:0:0:1".equals(normalized)) {
            return true;
        }
        return ipv4LoopbackLiteral(normalized);
    }

    private static boolean ipv4LoopbackLiteral(String host) {
        String[] octets = host.split("\\.", -1);
        if (octets.length != 4) return false;
        for (int index = 0; index < octets.length; index++) {
            String octet = octets[index];
            if (octet.isEmpty() || octet.length() > 3) return false;
            int value = 0;
            for (int character = 0; character < octet.length(); character++) {
                char digit = octet.charAt(character);
                if (digit < '0' || digit > '9') return false;
                value = value * 10 + (digit - '0');
            }
            if (value > 255 || (index == 0 && value != 127)) return false;
        }
        return true;
    }
}
