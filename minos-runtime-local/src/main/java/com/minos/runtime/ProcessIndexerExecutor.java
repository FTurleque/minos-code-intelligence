package com.minos.runtime;

import com.minos.io.BoundedInputStream;
import com.minos.orchestration.IndexArtifactLimits;
import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.orchestration.ProviderId;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Provider-independent process executor with optional qualified OS sandbox plan transformation. */
public final class ProcessIndexerExecutor implements IndexerExecutor {

    private final String indexerId;
    private final Path runsRoot;
    private final IndexerProcessPlanFactory planFactory;

    public ProcessIndexerExecutor(String indexerId, Path minosHome, IndexerProcessPlanFactory planFactory) {
        this.indexerId = ProviderId.require(indexerId);
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
        return executeSandboxed(request, (plan, runDirectory) -> plan);
    }

    IndexingArtifact executeSandboxed(IndexingExecutionRequest request, ProcessPlanTransformer transformer) throws Exception {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(transformer, "transformer");
        if (!indexerId.equals(request.selection().indexer().id())) {
            throw new IllegalArgumentException("request selected another indexer: "
                    + request.selection().indexer().id());
        }

        Path providerRunDirectory = runsRoot.resolve(request.runId().toString()).resolve(indexerId)
                .toAbsolutePath().normalize();
        if (!providerRunDirectory.startsWith(runsRoot)) {
            throw new IllegalStateException("provider run directory escapes MINOS runs root");
        }
        RunDirectoryRetention.prune(runsRoot, providerRunDirectory.getParent());
        Path runDirectory = scopedRunDirectory(providerRunDirectory, request.projectRelativeRoot());
        if (!runDirectory.toAbsolutePath().normalize().startsWith(providerRunDirectory)) {
            throw new IllegalStateException("provider scope directory escapes provider run root");
        }
        Files.createDirectories(runDirectory);
        IndexerProcessPlan original = Objects.requireNonNull(
                planFactory.create(request, runDirectory), "process plan");
        IndexerProcessPlan plan = Objects.requireNonNull(
                transformer.transform(original, runDirectory), "transformed process plan");
        if (plan.command().isEmpty() || plan.command().stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("provider command must contain non-null arguments");
        }
        if (!Files.isDirectory(plan.workingDirectory())) {
            throw new IllegalArgumentException("provider working directory is missing: " + plan.workingDirectory());
        }

        Path stdout = runDirectory.resolve("provider.stdout.log");
        Path stderr = runDirectory.resolve("provider.stderr.log");
        Path metadata = runDirectory.resolve("process.txt");
        Path finalArtifact = runDirectory.resolve("index.scip").toAbsolutePath().normalize();
        Path generatedArtifact = plan.generatedArtifact().toAbsolutePath().normalize();
        Path preservedArtifact = runDirectory.resolve("preexisting-artifact.scip");
        boolean artifactOutsideRun = !generatedArtifact.equals(finalArtifact);
        boolean preserveExisting = artifactOutsideRun && regularFileNoFollow(generatedArtifact);

        if (preserveExisting) {
            move(generatedArtifact, preservedArtifact);
        }

        Optional<ProviderWriteQuota> writeQuota = transformer.providerWriteQuota();
        Instant startedAt = Instant.now();
        writeMetadata(metadata, plan, request, startedAt);
        ProviderWriteQuotaSupervisor supervisor = null;
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(plan.command());
            processBuilder.directory(plan.workingDirectory().toFile());
            if (transformer.trustedLauncherRequiresParentEnvironment()) {
                // The transformed command is a MINOS-owned sandbox launcher, not provider code.
                // Provider environment isolation remains the launcher's responsibility and must be
                // encoded into the sandbox plan before this trusted boundary is selected.
                processBuilder.environment().putAll(plan.environment());
            } else {
                ProviderProcessEnvironment.apply(processBuilder, plan.environment());
            }

            Process process = processBuilder.start();
            if (writeQuota.isPresent()) {
                supervisor = ProviderWriteQuotaSupervisor.start(
                        providerWritableRoots(plan, runDirectory),
                        writeQuota.orElseThrow(),
                        () -> {
                            transformer.killContainedJob();
                            terminate(process);
                        });
            }
            BoundedProcessOutput.Capture outputCapture = BoundedProcessOutput.capture(process, stdout, stderr);
            boolean completed = process.waitFor(plan.timeout().toMillis(), TimeUnit.MILLISECONDS);
            Optional<String> breach = supervisor == null ? Optional.empty() : supervisor.breach();
            if (breach.isPresent()) {
                transformer.killContainedJob();
                terminate(process);
                appendQuietly(metadata, "status=WRITE_QUOTA_BREACH\ncompletedAt=" + Instant.now() + "\n");
                throw new IllegalStateException("provider write containment breached: " + breach.orElseThrow());
            }
            if (!completed) {
                transformer.killContainedJob();
                terminate(process);
                BoundedProcessOutput.Result output = outputCapture.await();
                appendOutputMetadata(metadata, output);
                append(metadata, "status=TIMEOUT\ncompletedAt=" + Instant.now() + "\n");
                throw new IllegalStateException("provider timed out after " + plan.timeout());
            }
            int exitCode = process.exitValue();
            transformer.killContainedJob();
            terminateDescendants(process);
            BoundedProcessOutput.Result output;
            try {
                output = outputCapture.await();
            } catch (IOException exception) {
                terminate(process);
                throw exception;
            }
            appendOutputMetadata(metadata, output);
            append(metadata, "exitCode=" + exitCode + "\ncompletedAt=" + Instant.now() + "\n");
            if (exitCode != 0) {
                archiveFailedArtifact(generatedArtifact, runDirectory);
                throw new IllegalStateException("provider exited with code " + exitCode + "; see " + stderr);
            }
            requireValidArtifact(generatedArtifact, "provider did not produce a valid SCIP artifact");

            if (!generatedArtifact.equals(finalArtifact)) {
                Path partial = runDirectory.resolve("index.partial.scip");
                copyArtifactBounded(generatedArtifact, partial);
                move(partial, finalArtifact);
            }
            requireValidArtifact(finalArtifact, "stable run artifact is invalid");

            return new IndexingArtifact(
                    request.selection().language(), indexerId, finalArtifact, request.projectRelativeRoot());
        } finally {
            if (supervisor != null) supervisor.close();
            transformer.releaseContainment();
            if (artifactOutsideRun) {
                Files.deleteIfExists(generatedArtifact);
                if (preserveExisting && regularFileNoFollow(preservedArtifact)) {
                    move(preservedArtifact, generatedArtifact);
                }
            }
            if (writeQuota.isPresent()) {
                ProviderResidueReclamation.reclaim(runsRoot, runDirectory);
            }
        }
    }

    /** Every root the transformed plan makes writable for the provider. */
    private static Set<Path> providerWritableRoots(IndexerProcessPlan plan, Path runDirectory) {
        Set<Path> roots = new LinkedHashSet<>();
        roots.add(plan.workingDirectory().toAbsolutePath().normalize());
        Path artifactParent = plan.generatedArtifact().toAbsolutePath().normalize().getParent();
        if (artifactParent != null) roots.add(artifactParent);
        roots.add(runDirectory.toAbsolutePath().normalize());
        return roots;
    }

    @FunctionalInterface
    interface ProcessPlanTransformer {
        IndexerProcessPlan transform(IndexerProcessPlan plan, Path runDirectory) throws Exception;

        default boolean trustedLauncherRequiresParentEnvironment() {
            return false;
        }

        /**
         * Write budget MINOS enforces on the provider during execution. An empty value means the
         * plan is trusted MINOS-side work, not an untrusted provider inside an OS job boundary.
         */
        default Optional<ProviderWriteQuota> providerWriteQuota() {
            return Optional.empty();
        }

        /** Destroys the whole OS job boundary so that no descendant can survive MINOS. */
        default void killContainedJob() {
        }

        /** Reclaims the OS job boundary itself after the provider terminated. */
        default void releaseContainment() {
        }
    }

    private static Path scopedRunDirectory(Path providerRunDirectory, Path relativeRoot) {
        if (relativeRoot == null || relativeRoot.toString().isEmpty()) return providerRunDirectory;
        return providerRunDirectory.resolve("scopes").resolve("module-" + scopeHash(relativeRoot));
    }

    private static String scopeHash(Path relativeRoot) {
        String portable = relativeRoot.normalize().toString().replace('\\', '/');
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(portable.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static void archiveFailedArtifact(Path generatedArtifact, Path runDirectory) throws IOException {
        if (!regularFileNoFollow(generatedArtifact)) return;
        Path failed = runDirectory.resolve("failed-index.scip");
        long size = Files.size(generatedArtifact);
        if (size <= IndexArtifactLimits.MAX_SCIP_ARTIFACT_BYTES) {
            copyArtifactBounded(generatedArtifact, failed);
        }
    }

    private static void requireValidArtifact(Path artifact, String message) throws IOException {
        if (!regularFileNoFollow(artifact)) {
            throw new IllegalStateException(message + ": " + artifact);
        }
        long size = Files.size(artifact);
        if (size < 1L || size > IndexArtifactLimits.MAX_SCIP_ARTIFACT_BYTES) {
            throw new IllegalStateException(message + ": size=" + size + "/"
                    + IndexArtifactLimits.MAX_SCIP_ARTIFACT_BYTES + " path=" + artifact);
        }
    }

    private static void copyArtifactBounded(Path source, Path target) throws IOException {
        Files.deleteIfExists(target);
        try (InputStream raw = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS);
             BoundedInputStream input = new BoundedInputStream(
                     raw, IndexArtifactLimits.MAX_SCIP_ARTIFACT_BYTES, "SCIP artifact copy");
             OutputStream output = Files.newOutputStream(
                     target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            input.transferTo(output);
        } catch (Exception exception) {
            Files.deleteIfExists(target);
            throw exception;
        }
    }

    private static boolean regularFileNoFollow(Path file) {
        return !Files.isSymbolicLink(file) && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS);
    }

    private static void terminateDescendants(Process process) {
        List<ProcessHandle> descendants = new ArrayList<>(process.descendants().toList());
        descendants.reversed().forEach(handle -> {
            if (handle.isAlive()) handle.destroyForcibly();
        });
    }

    private static void terminate(Process process) {
        terminateDescendants(process);
        if (process.isAlive()) process.destroyForcibly();
        try {
            process.waitFor(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static void writeMetadata(
            Path file,
            IndexerProcessPlan plan,
            IndexingExecutionRequest request,
            Instant startedAt
    ) throws IOException {
        StringBuilder value = new StringBuilder();
        value.append("startedAt=").append(startedAt).append('\n');
        value.append("registeredProjectRoot=").append(request.registeredProjectRoot()).append('\n');
        value.append("projectRelativeRoot=")
                .append(request.projectRelativeRoot().toString().replace('\\', '/')).append('\n');
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

    private static void appendOutputMetadata(Path metadata, BoundedProcessOutput.Result output) throws IOException {
        append(metadata, "stdoutTruncated=" + output.stdoutTruncated()
                + "\nstderrTruncated=" + output.stderrTruncated() + "\n");
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
                rendered.add(separator >= 0
                        ? argument.substring(0, separator + 1) + "<redacted>"
                        : "<redacted>");
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
        Files.writeString(file, value, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    private static void appendQuietly(Path file, String value) {
        try {
            append(file, value);
        } catch (IOException ignored) {
            // A containment breach is reported by the thrown failure, never hidden behind metadata IO.
        }
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
