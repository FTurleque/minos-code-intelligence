package com.minos.runtime;

import com.minos.io.BoundedInputStream;
import com.minos.io.DurableAtomicFile;
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
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Provider-independent process executor with optional qualified OS sandbox plan transformation. */
public final class ProcessIndexerExecutor implements ProcessSandboxCapableIndexerExecutor {

    private static final Duration GRACEFUL_TERMINATION_WAIT = Duration.ofMillis(500);
    private static final Duration FORCED_TERMINATION_WAIT = Duration.ofSeconds(10);

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

    @Override
    public IndexingArtifact executeSandboxed(IndexingExecutionRequest request, ProcessPlanTransformer transformer) throws Exception {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(transformer, "transformer");
        if (!indexerId.equals(request.selection().indexer().id())) {
            throw new IllegalArgumentException("request selected another indexer: "
                    + request.selection().indexer().id());
        }

        Path runDirectory = prepareRunDirectory(request);
        // Arm containment cleanup before transform(): Linux transforms may create the cgroup and
        // then fail during plan validation or diagnostics preparation before a provider is started.
        try (ContainmentRelease ignored = new ContainmentRelease(transformer)) {
            return executePrepared(request, transformer, runDirectory);
        }
    }

    private IndexingArtifact executePrepared(
            IndexingExecutionRequest request,
            ProcessPlanTransformer transformer,
            Path runDirectory
    ) throws Exception {
        IndexerProcessPlan plan = preparePlan(request, transformer, runDirectory);

        Path stdout = runDirectory.resolve("provider.stdout.log");
        Path stderr = runDirectory.resolve("provider.stderr.log");
        Path metadata = runDirectory.resolve("process.txt");
        Path finalArtifact = runDirectory.resolve("index.scip").toAbsolutePath().normalize();
        Path generatedArtifact = plan.generatedArtifact().toAbsolutePath().normalize();
        Path preservedArtifact = runDirectory.resolve("preexisting-artifact.scip");
        boolean artifactOutsideRun = !generatedArtifact.equals(finalArtifact);
        boolean preserveExisting = artifactOutsideRun && regularFileNoFollow(generatedArtifact);
        Optional<ProviderWriteQuota> writeQuota = transformer.providerWriteQuota();
        ProviderWriteQuotaSupervisor supervisor = null;

        try {
            if (preserveExisting) {
                move(generatedArtifact, preservedArtifact);
            }

            // Open every MINOS-owned diagnostic sink before untrusted provider code starts. The
            // provider may later replace these pathnames in a shared writable directory, but host
            // writes continue through already-open descriptors and can never be redirected by a
            // symlink race outside the sandbox boundary.
            try (OutputStream stdoutSink = BoundedProcessOutput.openTarget(stdout);
                 OutputStream stderrSink = BoundedProcessOutput.openTarget(stderr);
                 OutputStream metadataSink = BoundedProcessOutput.openTarget(metadata)) {
                Instant startedAt = Instant.now();
                writeMetadata(metadataSink, plan, request, startedAt);

                Process process = startProvider(plan, transformer);
                try (ProcessOwnershipTracker ownership = new ProcessOwnershipTracker(process)) {
                    supervisor = startWriteQuotaSupervisor(writeQuota, plan, runDirectory, transformer, process);
                    BoundedProcessOutput.Capture outputCapture =
                            BoundedProcessOutput.capture(process, stdoutSink, stderrSink);
                    boolean completed = process.waitFor(plan.timeout().toMillis(), TimeUnit.MILLISECONDS);
                    Optional<String> breach = supervisor == null ? Optional.empty() : supervisor.breach();
                    if (breach.isPresent()) {
                        transformer.killContainedJob();
                        ownership.terminate();
                        appendQuietly(metadataSink, "status=WRITE_QUOTA_BREACH\ncompletedAt=" + Instant.now() + "\n");
                        throw new IllegalStateException("provider write containment breached: " + breach.orElseThrow());
                    }
                    if (!completed) {
                        transformer.killContainedJob();
                        ownership.terminate();
                        BoundedProcessOutput.Result output = outputCapture.await();
                        appendOutputMetadata(metadataSink, output);
                        append(metadataSink, "status=TIMEOUT\ncompletedAt=" + Instant.now() + "\n");
                        throw new IllegalStateException("provider timed out after " + plan.timeout());
                    }
                    int exitCode = process.exitValue();
                    transformer.killContainedJob();
                    ownership.terminate();
                    BoundedProcessOutput.Result output = outputCapture.await();
                    appendOutputMetadata(metadataSink, output);
                    append(metadataSink, "exitCode=" + exitCode + "\ncompletedAt=" + Instant.now() + "\n");
                    if (exitCode != 0) {
                        archiveFailedArtifact(generatedArtifact, runDirectory);
                        throw new IllegalStateException("provider exited with code " + exitCode + "; see " + stderr);
                    }
                    promoteArtifact(generatedArtifact, finalArtifact, runDirectory);

                    return new IndexingArtifact(
                            request.selection().language(), indexerId, finalArtifact, request.projectRelativeRoot());
                }
            }
        } finally {
            if (supervisor != null) supervisor.close();
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

    private static Process startProvider(IndexerProcessPlan plan, ProcessPlanTransformer transformer)
            throws IOException {
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
        return processBuilder.start();
    }

    /** Enforces the provider write budget for as long as the provider runs. */
    private static ProviderWriteQuotaSupervisor startWriteQuotaSupervisor(
            Optional<ProviderWriteQuota> writeQuota,
            IndexerProcessPlan plan,
            Path runDirectory,
            ProcessPlanTransformer transformer,
            Process process
    ) {
        if (writeQuota.isEmpty()) return null;
        return ProviderWriteQuotaSupervisor.start(
                providerWritableRoots(plan, runDirectory),
                writeQuota.orElseThrow(),
                () -> {
                    transformer.killContainedJob();
                    terminate(process);
                });
    }

    private static void promoteArtifact(Path generatedArtifact, Path finalArtifact, Path runDirectory)
            throws IOException {
        requireValidArtifact(generatedArtifact, "provider did not produce a valid SCIP artifact");
        if (!generatedArtifact.equals(finalArtifact)) {
            Path partial = runDirectory.resolve("index.partial.scip");
            copyArtifactBounded(generatedArtifact, partial);
            move(partial, finalArtifact);
        }
        requireValidArtifact(finalArtifact, "stable run artifact is invalid");
    }

    /** Resolves and bounds the MINOS-owned run directory this execution may write to. */
    private Path prepareRunDirectory(IndexingExecutionRequest request) throws IOException {
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
        return runDirectory;
    }

    /** Builds the provider plan and lets the sandbox backend own the containment boundary. */
    private IndexerProcessPlan preparePlan(
            IndexingExecutionRequest request,
            ProcessPlanTransformer transformer,
            Path runDirectory
    ) throws Exception {
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
        return plan;
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

    /** Ensures transform-created OS resources are reclaimed even if validation fails pre-start. */
    private static final class ContainmentRelease implements AutoCloseable {
        private final ProcessPlanTransformer transformer;

        private ContainmentRelease(ProcessPlanTransformer transformer) {
            this.transformer = transformer;
        }

        @Override
        public void close() {
            transformer.releaseContainment();
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

    /**
     * Fallback teardown for the provider tree. The contained job is destroyed by the caller first;
     * this only sweeps the handles MINOS observed, under the shared policy that never targets the
     * host JVM and never waits without a bound. The grace window is deliberately short because this
     * also serves the write-quota breach path, where containment latency matters.
     */
    private static void terminate(Process process) {
        ProcessTreeTermination.terminateTree(process, GRACEFUL_TERMINATION_WAIT, FORCED_TERMINATION_WAIT);
    }

    private static void writeMetadata(
            OutputStream output,
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
        append(output, value.toString());
    }

    private static void appendOutputMetadata(OutputStream metadata, BoundedProcessOutput.Result output) throws IOException {
        append(metadata, "stdoutTruncated=" + output.stdoutTruncated()
                + "\nstderrTruncated=" + output.stderrTruncated() + "\n");
    }

    /**
     * Provider command arguments are intentionally never persisted.
     *
     * <p>A blacklist of token/password/secret spellings is not a confidentiality boundary: provider
     * CLIs can use arbitrary option names such as api-key, authorization, credential or vendor-
     * specific flags. Keeping only argument cardinality preserves useful diagnostics while making
     * the persisted metadata independent of future credential naming conventions.</p>
     */
    private static String redactedCommand(List<String> command) {
        Objects.requireNonNull(command, "command");
        int argumentCount = Math.max(0, command.size() - 1);
        return "<redacted provider command; argumentCount=" + argumentCount + ">";
    }

    private static void append(OutputStream output, String value) throws IOException {
        output.write(value.getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private static void appendQuietly(OutputStream output, String value) {
        try {
            append(output, value);
        } catch (IOException ignored) {
            // A containment breach is reported by the thrown failure, never hidden behind metadata IO.
        }
    }

    private static void move(Path source, Path target) throws IOException {
        Path normalizedTarget = target.toAbsolutePath().normalize();
        DurableAtomicFile.ensureDirectory(normalizedTarget.getParent(), "provider artifact target directory");
        try {
            DurableAtomicFile.replace(source, normalizedTarget, "provider artifact replacement");
            return;
        } catch (IOException failure) {
            if (!(failure.getCause() instanceof AtomicMoveNotSupportedException)) throw failure;
        }

        // Provider plans may place their generated artifact on another filesystem. Never degrade to a
        // non-atomic move: copy under the existing SCIP byte budget into the target filesystem, then
        // publish that copy with the same durable atomic primitive used by authoritative local stores.
        Path localCopy = Files.createTempFile(normalizedTarget.getParent(), ".artifact-transfer-", ".tmp");
        Files.deleteIfExists(localCopy);
        try {
            copyArtifactBounded(source, localCopy);
            DurableAtomicFile.replace(localCopy, normalizedTarget, "cross-filesystem provider artifact replacement");
            Files.delete(source);
        } finally {
            Files.deleteIfExists(localCopy);
        }
    }
}
