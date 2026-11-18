package com.minos.orchestration;

import com.minos.incremental.ProjectFingerprintService;
import com.minos.orchestration.IndexingRun.ExecutionCheckpoint;
import com.minos.orchestration.IndexingRun.IndexerExecution;
import com.minos.orchestration.IndexingRuntimePorts.ExecutionPathAuthorization;
import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static com.minos.orchestration.IndexingRunContext.portable;

/**
 * Runs the providers of the targets of one run, records each artifact with its checkpoint, and confines every
 * artifact to the run directory before anything reads it. One instance per run.
 */
final class IndexingTargetExecution {

    /** Bounded tolerance for a freshly-written artifact briefly appearing unreadable to this
     * process -- e.g. a real-time antivirus scan holding a transient handle on it -- immediately
     * after the provider process that wrote it has already exited. This is MINOS' own run
     * directory, not attacker-controlled input, so a short poll here carries none of the
     * containment implications a provider-controlled path would. */
    private static final int ARTIFACT_READABILITY_RETRY_ATTEMPTS = 10;
    private static final long ARTIFACT_READABILITY_RETRY_DELAY_MILLIS = 100L;

    private final IndexingRunContext context;
    private final ValidatedProjectRoot root;

    IndexingTargetExecution(IndexingRunContext context, ValidatedProjectRoot root) {
        this.context = Objects.requireNonNull(context, "context");
        this.root = Objects.requireNonNull(root, "root");
    }

    void executeProviders(List<IndexingExecutionTarget> targets, IndexingMode mode, List<String> changedFiles)
            throws Exception {
        for (IndexingExecutionTarget target : targets) {
            executeProvider(target, mode, changedFiles);
        }
    }

    private void executeProvider(IndexingExecutionTarget target, IndexingMode mode, List<String> changedFiles)
            throws Exception {
        var selection = target.selection();
        String indexerId = selection.indexer().id();
        IndexerExecutor executor = requireExecutor(context.ports.executors(), indexerId);
        Path relative = target.projectRelativeRoot();
        Path executionRoot = requireExecutionRoot(relative);
        List<String> scopedChangedFiles = scopedChangedFiles(mode, changedFiles, relative);
        // The scope fingerprint is captured once per scope, before the first provider of that scope
        // starts (V3: N root providers cost one project hash, not N). A source that changes after the
        // capture differs from the recorded fingerprint, so a later resume can never reuse an artifact
        // built from sources it did not fingerprint (fail-closed, ADR 0039 §3). Beyond the source
        // budget (100 000 files / 2 GiB) the capture fails and no checkpoint is persisted.
        // AUD-PERF-04: the root scope reuses the reference fingerprint of the orchestration when its rules are proved
        // equal (ReferenceFingerprint); every other scope, and an unproved one, is captured as before.
        CheckpointMaterial material = context.scopeFingerprints.computeIfAbsent(
                relative.normalize(), scope -> scope.toString().isEmpty() && context.ports.rootScopeFingerprint().isPresent()
                        ? new CheckpointMaterial(context.ports.rootScopeFingerprint(), Optional.empty())
                        : captureScopeFingerprint(context.ports.fingerprints(), root.lexicalRoot(), scope));
        IndexingArtifact artifact = Objects.requireNonNull(executor.execute(new IndexingExecutionRequest(
                context.runId,
                context.projectId,
                root.lexicalRoot(),
                executionRoot,
                relative,
                selection,
                mode,
                scopedChangedFiles,
                ExecutionPathAuthorization.tryCapture(root.lexicalRoot(), executionRoot),
                registeredProjectFingerprint())), "indexer execution artifact");
        Path artifactPath = validateArtifact(executor, selection, artifact, relative);
        CheckpointOutcome checkpoint = checkpoint(
                material, artifactPath, relative, selection.indexer().version(), mode, scopedChangedFiles,
                context.ports.clock().instant());
        if (checkpoint.withheldReason().isPresent()) context.withheldCheckpoints++;
        context.record(target,
                new IndexingArtifact(artifact.language(), artifact.indexerId(), artifactPath, relative),
                new IndexerExecution(artifact.language(), artifact.indexerId(), artifactPath, checkpoint.checkpoint()));
        context.ports.stateStore().saveRun(context.running(
                "provider artifacts completed: " + context.executions().size()
                        + "/" + context.totalTargets() + ", mode=" + mode + ", scope=" + portable(relative)
                        + checkpoint.withheldReason().map(reason -> ", checkpoint withheld: " + reason).orElse("")));
    }

    /**
     * Fingerprint of the whole registered project already known to the run (AUD-PERF-07): the reference fingerprint
     * when it is reusable, otherwise the root scope captured earlier in this run, otherwise none. Never captured here,
     * so a run without a root scope costs no extra hash.
     */
    private Optional<String> registeredProjectFingerprint() {
        if (context.ports.rootScopeFingerprint().isPresent()) {
            return context.ports.rootScopeFingerprint();
        }
        CheckpointMaterial rootScope = context.scopeFingerprints.get(Path.of(""));
        return rootScope == null ? Optional.empty() : rootScope.scopeFingerprint();
    }

    private static CheckpointMaterial captureScopeFingerprint(
            ProjectFingerprintService fingerprints, Path projectRoot, Path relative) {
        try {
            return new CheckpointMaterial(
                    Optional.of(ExecutionCheckpoints.scopeFingerprint(fingerprints, projectRoot, relative)),
                    Optional.empty());
        } catch (ExecutionCheckpoints.Unavailable unavailable) {
            return new CheckpointMaterial(Optional.empty(), Optional.of(unavailable.getMessage()));
        }
    }

    /**
     * Builds the durable checkpoint of one completed target, or explains why none can be trusted.
     * A withheld checkpoint never changes the outcome of the run: the target simply stays
     * non-resumable.
     */
    private static CheckpointOutcome checkpoint(
            CheckpointMaterial material,
            Path artifactPath,
            Path relative,
            String providerVersion,
            IndexingMode mode,
            List<String> scopedChangedFiles,
            Instant completedAt
    ) {
        if (material.scopeFingerprint().isEmpty()) {
            return CheckpointOutcome.withheld(material.withheldReason().orElse("scope fingerprint unavailable"));
        }
        if (scopedChangedFiles.size() > ExecutionCheckpoint.MAX_CHANGED_FILES) {
            return CheckpointOutcome.withheld("changed-file list exceeds checkpoint budget");
        }
        try {
            ExecutionCheckpoints.ArtifactDigest digest = ExecutionCheckpoints.artifactDigest(artifactPath);
            return CheckpointOutcome.recorded(new ExecutionCheckpoint(
                    relative,
                    providerVersion,
                    digest.bytes(),
                    digest.sha256(),
                    material.scopeFingerprint().orElseThrow(),
                    mode,
                    scopedChangedFiles,
                    completedAt));
        } catch (ExecutionCheckpoints.Unavailable unavailable) {
            return CheckpointOutcome.withheld(unavailable.getMessage());
        } catch (IllegalArgumentException invalid) {
            return CheckpointOutcome.withheld("checkpoint material rejected");
        }
    }

    private static IndexerExecutor requireExecutor(Map<String, IndexerExecutor> executors, String indexerId) {
        IndexerExecutor executor = executors.get(indexerId);
        if (executor == null) {
            throw new IllegalStateException("No runtime executor registered for indexer: " + indexerId);
        }
        return executor;
    }

    private Path requireExecutionRoot(Path relative) {
        Path executionRoot = root.lexicalRoot().resolve(relative).normalize();
        if (!executionRoot.startsWith(root.lexicalRoot()) || !Files.isDirectory(executionRoot)) {
            throw new IllegalStateException(
                    "provider execution root is missing or outside project: " + portable(relative));
        }
        try {
            Path realExecutionRoot = executionRoot.toRealPath();
            if (!realExecutionRoot.startsWith(root.realRoot())) {
                throw new IllegalStateException(
                        "provider execution root resolves outside project: " + portable(relative));
            }
            // Physical authorization is decided only on canonical paths. The request itself keeps
            // lexical paths so registeredRoot + relativeRoot remains stable even when Windows
            // expands an 8.3 path (or another filesystem alias) during toRealPath().
            return executionRoot;
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "provider execution root could not be resolved safely: " + portable(relative), failure);
        }
    }

    /** Changed files of an incremental run, relative to the scope, as the provider receives them. */
    static List<String> scopedChangedFiles(IndexingMode mode, List<String> changedFiles, Path relative) {
        if (mode != IndexingMode.INCREMENTAL || relative.toString().isEmpty()) return changedFiles;
        String prefix = portable(relative) + "/";
        return changedFiles.stream().filter(path -> path.startsWith(prefix))
                .map(path -> path.substring(prefix.length())).sorted().toList();
    }

    private Path validateArtifact(IndexerExecutor executor, IndexerNegotiationResult.IndexerSelection selection,
                                  IndexingArtifact artifact, Path expectedRoot) throws InterruptedException {
        if (artifact.language() != selection.language()) throw new IllegalStateException("executor returned an artifact for an unexpected language");
        if (!artifact.indexerId().equals(selection.indexer().id())) throw new IllegalStateException("executor returned an artifact for an unexpected indexer");
        if (!artifact.projectRelativeRoot().normalize().equals(expectedRoot.normalize())) throw new IllegalStateException("executor returned an artifact for an unexpected project scope");
        Path path = artifact.finalArtifact().toAbsolutePath().normalize();
        if (!awaitReadable(path)) throw new IllegalStateException("final index artifact is missing or unreadable");
        requireInsideRunDirectory(executor, path);
        return path;
    }

    /**
     * Q5: the artifact an executor returns stays in the run directory, physically (no {@code ..}, no link at any
     * level of its path), before any checkpoint or staging reads it. Without a known run directory there is nothing to confine
     * to (and no resume is possible either); an executor with a store of its own is confined by that store.
     */
    private void requireInsideRunDirectory(IndexerExecutor executor, Path artifact) {
        if (!executor.artifactsLiveInRunDirectory()) return;
        Optional<Path> runDirectory = context.ports.markers().runDirectory(context.runId);
        if (runDirectory.isEmpty()) return;
        try {
            ArtifactConfinement.requireInside(runDirectory.orElseThrow(), artifact);
        } catch (ArtifactConfinement.Escape escape) {
            throw new IllegalStateException("executor returned an artifact that "
                    + escape.reason().phrase(), escape);
        }
    }

    /**
     * V-L4-03: an artifact was confined when its provider returned, and the providers of the other scopes have run
     * since. Every artifact, fresh or reused, is confined again right before the snapshot reads it: the same single
     * decision, the same single place for the answer.
     */
    void requireArtifactsStillInsideRunDirectory() {
        for (IndexingArtifact artifact : context.artifacts()) {
            IndexerExecutor executor = context.ports.executors().get(artifact.indexerId());
            if (executor != null) requireInsideRunDirectory(executor, artifact.finalArtifact());
        }
    }

    /**
     * Waits, bounded, for a freshly written artifact to become readable. An interruption is never
     * folded into "the artifact is missing" (R6): it propagates, so that the run ends INTERRUPTED and
     * resumable and not FAILED.
     */
    static boolean awaitReadable(Path path) throws InterruptedException {
        for (int attempt = 1; attempt <= ARTIFACT_READABILITY_RETRY_ATTEMPTS; attempt++) {
            if (Files.exists(path) && Files.isReadable(path)) return true;
            if (attempt == ARTIFACT_READABILITY_RETRY_ATTEMPTS) return false;
            Thread.sleep(ARTIFACT_READABILITY_RETRY_DELAY_MILLIS);
        }
        return false;
    }

    /** The project root of a run, lexical and canonical, validated once before any provider runs. */
    record ValidatedProjectRoot(Path lexicalRoot, Path realRoot) {
        ValidatedProjectRoot {
            Objects.requireNonNull(lexicalRoot, "lexicalRoot");
            Objects.requireNonNull(realRoot, "realRoot");
        }
    }

    record CheckpointMaterial(Optional<String> scopeFingerprint, Optional<String> withheldReason) {
        CheckpointMaterial {
            Objects.requireNonNull(scopeFingerprint, "scopeFingerprint");
            Objects.requireNonNull(withheldReason, "withheldReason");
        }
    }

    private record CheckpointOutcome(Optional<ExecutionCheckpoint> checkpoint, Optional<String> withheldReason) {
        private CheckpointOutcome {
            Objects.requireNonNull(checkpoint, "checkpoint");
            Objects.requireNonNull(withheldReason, "withheldReason");
            if (checkpoint.isPresent() == withheldReason.isPresent()) {
                throw new IllegalArgumentException("a checkpoint is either recorded or withheld with a reason");
            }
        }

        private static CheckpointOutcome recorded(ExecutionCheckpoint checkpoint) {
            return new CheckpointOutcome(Optional.of(checkpoint), Optional.empty());
        }

        private static CheckpointOutcome withheld(String reason) {
            return new CheckpointOutcome(Optional.empty(), Optional.of(reason));
        }
    }
}
