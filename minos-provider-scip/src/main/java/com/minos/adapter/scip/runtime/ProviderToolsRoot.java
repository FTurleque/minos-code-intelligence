package com.minos.adapter.scip.runtime;

import java.nio.file.Path;
import java.util.Objects;

/** Resolves the immutable/provider tool root independently from mutable MINOS business state. */
final class ProviderToolsRoot {
    static final String ENVIRONMENT = "MINOS_PROVIDER_TOOLS_ROOT";
    static final String SYSTEM_PROPERTY = "minos.provider.toolsRoot";

    private ProviderToolsRoot() {
    }

    static Path resolve(Path minosHome) {
        Path home = Objects.requireNonNull(minosHome, "minosHome").toAbsolutePath().normalize();
        String configured = System.getProperty(SYSTEM_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(ENVIRONMENT);
        }
        if (configured == null || configured.isBlank()) {
            return home.resolve("tools");
        }
        Path root = Path.of(configured.trim());
        if (!root.isAbsolute()) {
            throw new IllegalArgumentException(ENVIRONMENT + " must be an absolute path: " + configured);
        }
        return root.normalize();
    }
}
