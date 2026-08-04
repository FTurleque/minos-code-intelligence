package com.minos.cli;

import java.time.Duration;
import java.util.Objects;

/** Versioned configuration consumed by the stable {@code minos mcp} entry point. */
public record McpBackendConfiguration(
        int formatVersion,
        McpBackend backend,
        String dockerContainerName,
        Duration dockerProbeTimeout
) {
    public static final int CURRENT_FORMAT_VERSION = 1;
    public static final String DEFAULT_DOCKER_CONTAINER = "minos-mcp-prod";
    public static final Duration DEFAULT_DOCKER_PROBE_TIMEOUT = Duration.ofSeconds(10);

    public McpBackendConfiguration {
        if (formatVersion != CURRENT_FORMAT_VERSION) {
            throw new IllegalArgumentException("unsupported MCP backend configuration format: " + formatVersion);
        }
        Objects.requireNonNull(backend, "backend");
        if (dockerContainerName == null || !dockerContainerName.matches("[A-Za-z0-9][A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("invalid Docker container name: " + dockerContainerName);
        }
        Objects.requireNonNull(dockerProbeTimeout, "dockerProbeTimeout");
        if (dockerProbeTimeout.isNegative() || dockerProbeTimeout.isZero() || dockerProbeTimeout.compareTo(Duration.ofMinutes(2)) > 0) {
            throw new IllegalArgumentException("Docker probe timeout must be between 1 ms and 2 minutes");
        }
    }

    public static McpBackendConfiguration nativeDefault() {
        return new McpBackendConfiguration(
                CURRENT_FORMAT_VERSION,
                McpBackend.NATIVE,
                DEFAULT_DOCKER_CONTAINER,
                DEFAULT_DOCKER_PROBE_TIMEOUT
        );
    }
}
