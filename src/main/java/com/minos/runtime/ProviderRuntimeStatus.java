package com.minos.runtime;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Diagnostic actionnable d'un runtime provider installé ou disponible.
 */
public record ProviderRuntimeStatus(
        String providerId,
        String version,
        State state,
        Optional<Path> executable,
        List<String> diagnostics
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
        INVALID
    }
}
