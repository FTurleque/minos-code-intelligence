package com.minos.runtime.local.process;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Provider-independent description of one external indexing process.
 *
 * <p>{@code readOnlyRoots} are host directories the provider must read and never write (a Maven repository
 * designated by the user, for instance); {@code maskedFiles} are host files the provider must see empty. Both are
 * sandbox mounts: only an OS sandbox that mounts them exactly may run such a plan, every other boundary refuses it
 * before launch. Each transformation of a plan keeps them through {@link #withCommand} or
 * {@link #withEnvironment}.</p>
 */
public record IndexerProcessPlan(
        List<String> command,
        Path workingDirectory,
        Map<String, String> environment,
        Path generatedArtifact,
        Duration timeout,
        List<Path> readOnlyRoots,
        List<Path> maskedFiles
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
        readOnlyRoots = List.copyOf(Objects.requireNonNull(readOnlyRoots, "readOnlyRoots"));
        maskedFiles = List.copyOf(Objects.requireNonNull(maskedFiles, "maskedFiles"));
    }

    /** A plan that declares no sandbox mount. */
    public IndexerProcessPlan(
            List<String> command,
            Path workingDirectory,
            Map<String, String> environment,
            Path generatedArtifact,
            Duration timeout
    ) {
        this(command, workingDirectory, environment, generatedArtifact, timeout, List.of(), List.of());
    }

    /** True when the plan declares a read-only root or a masked file, which only an OS sandbox can honour. */
    public boolean declaresSandboxMounts() {
        return !readOnlyRoots.isEmpty() || !maskedFiles.isEmpty();
    }

    /** The same plan run by {@code newCommand}, every other component, sandbox mounts included, kept. */
    public IndexerProcessPlan withCommand(List<String> newCommand) {
        return new IndexerProcessPlan(newCommand, workingDirectory, environment, generatedArtifact, timeout,
                readOnlyRoots, maskedFiles);
    }

    /** The same plan with {@code newEnvironment}, every other component, sandbox mounts included, kept. */
    public IndexerProcessPlan withEnvironment(Map<String, String> newEnvironment) {
        return new IndexerProcessPlan(command, workingDirectory, newEnvironment, generatedArtifact, timeout,
                readOnlyRoots, maskedFiles);
    }

    /**
     * Refuses a plan that declares sandbox mounts before a boundary that cannot mount them starts anything.
     *
     * @throws IllegalStateException naming the boundary, never a path
     */
    public void requireNoSandboxMounts(String boundary) {
        if (declaresSandboxMounts()) {
            throw new IllegalStateException(boundary + " cannot mount the read-only roots or masked files this "
                    + "provider plan declares; it runs only under the qualified Linux sandbox");
        }
    }
}
