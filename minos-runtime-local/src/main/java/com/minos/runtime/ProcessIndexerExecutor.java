package com.minos.runtime;

import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Exécuteur de processus fournisseur-indépendant.
 *
 * <p>Le provider décrit seulement la commande et l'artefact attendu. MINOS
 * sérialise le run par projet via {@code IndexingLifecycleService}, capture les
 * logs, préserve un éventuel artefact préexistant et ne retourne qu'une copie
 * stable conservée dans {@code MINOS_HOME/runs}.</p>
 */
public final class ProcessIndexerExecutor implements IndexerExecutor {

    private final String indexerId;
    private final Path runsRoot;
    private final IndexerProcessPlanFactory planFactory;

    public ProcessIndexerExecutor(String indexerId, Path minosHome, IndexerProcessPlanFactory planFactory) {
        if (indexerId == null || indexerId.isBlank()) {
            throw new IllegalArgumentException("indexerId must not be blank");
        }
        this.indexerId = indexerId;
        this.runsRoot = Objects.requireNonNull(minosHome, "minosHome")
                .toAbsolutePath().normalize().resolve("runs");
        this.planFactory = Objects.requireNonNull(planFactory, "planFactory");
    }

    @Override
    public String indexerId() {
        return indexerId;
    }

    @Override
    public IndexingArtifact execute(IndexingExecutionRequest request) throws Exception {
        Objects.requireNonNull(request, "request");
        if (!indexerId.equals(request.selection().indexer().id())) {
            throw new IllegalArgumentException("request selected another indexer: "
                    + request.selection().indexer().id());
        }

        Path runDirectory = runsRoot.resolve(request.runId().toString()).resolve(indexerId);
        Files.createDirectories(runDirectory);
        IndexerProcessPlan plan = Objects.requireNonNull(
                planFactory.create(request, runDirectory), "process plan");
        if (!Files.isDirectory(plan.workingDirectory())) {
            throw new IllegalArgumentException("provider working directory is missing: " + plan.workingDirectory());
        }

        Path stdout = runDirectory.resolve("provider.stdout.log");
        Path stderr = runDirectory.resolve("provider.stderr.log");
        Path metadata = runDirectory.resolve("process.txt");
        Path finalArtifact = runDirectory.resolve("index.scip").toAbsolutePath().normalize();
        Path generatedArtifact = plan.generatedArtifact();
        Path preservedArtifact = runDirectory.resolve("preexisting-artifact.scip");
        boolean artifactOutsideRun = !generatedArtifact.equals(finalArtifact);
        boolean preserveExisting = artifactOutsideRun && Files.isRegularFile(generatedArtifact);

        if (preserveExisting) {
            move(generatedArtifact, preservedArtifact);
        }

        Instant startedAt = Instant.now();
        writeMetadata(metadata, plan, startedAt);
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(plan.command());
            processBuilder.directory(plan.workingDirectory().toFile());
            processBuilder.environment().putAll(plan.environment());
            processBuilder.redirectOutput(stdout.toFile());
            processBuilder.redirectError(stderr.toFile());

            Process process = processBuilder.start();
            boolean completed = process.waitFor(plan.timeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                terminate(process);
                append(metadata, "status=TIMEOUT\ncompletedAt=" + Instant.now() + "\n");
                throw new IllegalStateException("provider timed out after " + plan.timeout());
            }
            int exitCode = process.exitValue();
            append(metadata, "exitCode=" + exitCode + "\ncompletedAt=" + Instant.now() + "\n");
            if (exitCode != 0) {
                archiveFailedArtifact(generatedArtifact, runDirectory);
                throw new IllegalStateException("provider exited with code " + exitCode
                        + "; see " + stderr);
            }
            if (!Files.isRegularFile(generatedArtifact) || Files.size(generatedArtifact) == 0L) {
                throw new IllegalStateException("provider did not produce a non-empty SCIP artifact: "
                        + generatedArtifact);
            }

            if (!generatedArtifact.equals(finalArtifact)) {
                Path partial = runDirectory.resolve("index.partial.scip");
                Files.copy(generatedArtifact, partial, StandardCopyOption.REPLACE_EXISTING);
                move(partial, finalArtifact);
            }
            if (!Files.isRegularFile(finalArtifact) || Files.size(finalArtifact) == 0L) {
                throw new IllegalStateException("stable run artifact is missing: " + finalArtifact);
            }

            return new IndexingArtifact(request.selection().language(), indexerId, finalArtifact);
        } finally {
            if (artifactOutsideRun) {
                Files.deleteIfExists(generatedArtifact);
                if (preserveExisting && Files.isRegularFile(preservedArtifact)) {
                    move(preservedArtifact, generatedArtifact);
                }
            }
        }
    }

    private static void archiveFailedArtifact(Path generatedArtifact, Path runDirectory) throws IOException {
        if (!Files.isRegularFile(generatedArtifact)) {
            return;
        }
        Path failed = runDirectory.resolve("failed-index.scip");
        Files.copy(generatedArtifact, failed, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void terminate(Process process) {
        List<ProcessHandle> descendants = new ArrayList<>(process.descendants().toList());
        descendants.reversed().forEach(handle -> {
            if (handle.isAlive()) {
                handle.destroyForcibly();
            }
        });
        if (process.isAlive()) {
            process.destroyForcibly();
        }
        try {
            process.waitFor(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static void writeMetadata(Path file, IndexerProcessPlan plan, Instant startedAt) throws IOException {
        StringBuilder value = new StringBuilder();
        value.append("startedAt=").append(startedAt).append('\n');
        value.append("workingDirectory=").append(plan.workingDirectory()).append('\n');
        value.append("generatedArtifact=").append(plan.generatedArtifact()).append('\n');
        value.append("timeout=").append(plan.timeout()).append('\n');
        value.append("command=").append(redactedCommand(plan.command())).append('\n');
        if (!plan.environment().isEmpty()) {
            value.append("environmentKeys=")
                    .append(String.join(",", plan.environment().keySet().stream().sorted().toList()))
                    .append('\n');
        }
        Files.writeString(file, value, StandardCharsets.UTF_8);
    }

    private static String redactedCommand(List<String> command) {
        List<String> rendered = new ArrayList<>(command.size());
        boolean redactNext = false;
        for (String argument : command) {
            if (redactNext) {
                rendered.add("<redacted>");
                redactNext = false;
                continue;
            }
            String lower = argument.toLowerCase(Locale.ROOT);
            if (lower.contains("token=") || lower.contains("password=") || lower.contains("secret=")) {
                int separator = argument.indexOf('=');
                rendered.add(separator >= 0 ? argument.substring(0, separator + 1) + "<redacted>" : "<redacted>");
                continue;
            }
            rendered.add(argument);
            if ("--token".equals(lower) || "--password".equals(lower) || "--secret".equals(lower)) {
                redactNext = true;
            }
        }
        return String.join(" ", rendered);
    }

    private static void append(Path file, String value) throws IOException {
        Files.writeString(file, value, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
    }

    private static void move(Path source, Path target) throws IOException {
        Files.createDirectories(target.toAbsolutePath().normalize().getParent());
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
