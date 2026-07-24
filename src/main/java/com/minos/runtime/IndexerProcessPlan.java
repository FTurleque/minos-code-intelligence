package com.minos.runtime;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Plan fournisseur-indépendant décrivant un processus d'indexation externe.
 */
public record IndexerProcessPlan(
        List<String> command,
        Path workingDirectory,
        Map<String, String> environment,
        Path generatedArtifact,
        Duration timeout
) {
    public IndexerProcessPlan {
        command = List.copyOf(Objects.requireNonNull(command, "command"));
        if (command.isEmpty() || command.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("command must contain non-blank arguments");
        }
        workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory")
                .toAbsolutePath().normalize();
        environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
        if (environment.entrySet().stream().anyMatch(entry -> entry.getKey() == null
                || entry.getKey().isBlank() || entry.getValue() == null)) {
            throw new IllegalArgumentException("environment must contain non-null values and non-blank keys");
        }
        generatedArtifact = Objects.requireNonNull(generatedArtifact, "generatedArtifact")
                .toAbsolutePath().normalize();
        timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }
}
