package com.minos.runtime;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Actionable diagnostic for one managed provider runtime. */
public record ProviderRuntimeStatus(
        String providerId,
        String version,
        State state,
        Optional<Path> executable,
        List<String> diagnostics,
        boolean requiredByDefault
) {
    public ProviderRuntimeStatus {
        providerId = requireText(providerId, "providerId");
        version = requireText(version, "version");
        Objects.requireNonNull(state, "state");
        executable = Objects.requireNonNull(executable, "executable")
                .map(path -> path.toAbsolutePath().normalize());
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
        if (diagnostics.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("diagnostics must not contain blank values");
        }
    }

    /** Compatibility constructor: historical managed providers are baseline-required. */
    public ProviderRuntimeStatus(
            String providerId,
            String version,
            State state,
            Optional<Path> executable,
            List<String> diagnostics
    ) {
        this(providerId, version, state, executable, diagnostics, true);
    }

    public boolean ready() {
        return state == State.READY;
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }

    public enum State {
        READY,
        NOT_INSTALLED,
        BLOCKED,
        INVALID,
        /**
         * The stronger local-sandbox tier this provider would otherwise be qualified against is not
         * provided by the currently selected backend (e.g. the Docker MCP admin/indexing plane, which
         * is its own hardened boundary but does not nest a second OS sandbox inside itself) -- not
         * because the provider itself is broken or unavailable. Execution still proceeds through the
         * existing, unchanged managed-local-provider fallback; this state exists so a capability
         * genuinely absent from a backend never silently reports READY, while also never blocking an
         * installation or verification that does not actually require it. Distinct from {@link
         * #BLOCKED}, which means the provider needed a sandbox this host should have been able to
         * qualify and could not -- that remains a real, blocking failure.
         */
        UNSUPPORTED_BY_BACKEND
    }
}
