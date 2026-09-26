package com.minos.orchestration;

import com.minos.discovery.ProjectDiscovery;
import com.minos.discovery.ProjectDiscovery.BuildSystem;
import com.minos.discovery.ProjectDiscovery.DiscoveredModule;
import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.discovery.ProjectDiscovery.SourceRoot;
import com.minos.discovery.ProjectDiscovery.SourceRootKind;
import com.minos.incremental.IncrementalIndexingPlan;
import com.minos.incremental.IncrementalIndexingPlanner;
import com.minos.incremental.ProjectChangeSet;
import com.minos.incremental.ProjectFingerprintService;
import com.minos.incremental.ProjectInvalidationAssessment;
import com.minos.incremental.ProjectInvalidationReason;
import com.minos.incremental.ProjectInvalidationScope;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;
import com.minos.orchestration.IndexingRun.ExecutionCheckpoint;
import com.minos.orchestration.IndexingRun.IndexerExecution;
import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R1 lot 1 : l'exécuteur renseigne un point de contrôle complet après chaque provider (ADR 0039 §1). */
class IndexingRunExecutorCheckpointTest {

    private static final Instant NOW = Instant.parse("2026-09-26T09:30:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String PROVIDER = "scip-typescript";
    private static final String PROVIDER_VERSION = "0.4.0";

    @Test
    void recordsACompleteCheckpointForEveryScopeAfterItsProviderCompletes(@TempDir Path temp) throws Exception {
        Path root = project(temp);
        Path appArtifact = Files.writeString(temp.resolve("app.scip"), "app-index-bytes");
        Path libArtifact = Files.writeString(temp.resolve("lib.scip"), "lib-index-bytes-longer");
        InMemoryIndexStateStore store = new InMemoryIndexStateStore();
        IndexingLifecycleService lifecycle = lifecycle(store, scopedExecutor(root, appArtifact, libArtifact, false));
        UUID projectId = UUID.randomUUID();

        IndexingRun run = lifecycle.execute(projectId, root, discovery(root), negotiation(false));

        assertEquals(IndexingRun.Status.SUCCEEDED, run.status());
        assertEquals(IndexingRun.CURRENT_FORMAT_VERSION, run.runFormatVersion());
        assertEquals(2, run.executions().size());
        ExecutionCheckpoint app = run.executions().get(0).checkpoint().orElseThrow();
        ExecutionCheckpoint lib = run.executions().get(1).checkpoint().orElseThrow();

        assertEquals(Path.of("ui/app"), app.projectRelativeRoot());
        assertEquals(Path.of("ui/lib"), lib.projectRelativeRoot());
        assertEquals(PROVIDER_VERSION, app.providerVersion());
        assertEquals(Files.size(appArtifact), app.artifactBytes());
        assertEquals(Files.size(libArtifact), lib.artifactBytes());
        assertEquals(sha256(Files.readAllBytes(appArtifact)), app.artifactSha256());
        assertEquals(sha256(Files.readAllBytes(libArtifact)), lib.artifactSha256());
        assertEquals(IndexingMode.FULL, app.mode());
        assertEquals(List.of(), app.changedFiles());
        assertEquals(NOW, app.completedAt());
        ProjectFingerprintService fingerprints = new ProjectFingerprintService();
        assertEquals(fingerprints.captureScope(root, Path.of("ui/app")).projectSha256(), app.scopeFingerprint());
        assertEquals(fingerprints.captureScope(root, Path.of("ui/lib")).projectSha256(), lib.scopeFingerprint());
        assertNotEquals(app.scopeFingerprint(), lib.scopeFingerprint());

        IndexingRun persisted = store.findRun(run.id()).orElseThrow();
        assertEquals(run.executions(), persisted.executions(), "checkpoints must be in the durable run trace");
        assertEquals(IndexingRun.targetKey(PROVIDER, PROVIDER_VERSION, Path.of("ui/app")),
                IndexingRun.targetKey(run.executions().get(0).indexerId(), app.providerVersion(), app.projectRelativeRoot()));
    }

    @Test
    void scopeFingerprintOnlyMovesWithTheScopeThatChanged(@TempDir Path temp) throws Exception {
        Path root = project(temp);
        Path appArtifact = Files.writeString(temp.resolve("app.scip"), "app");
        Path libArtifact = Files.writeString(temp.resolve("lib.scip"), "lib");
        InMemoryIndexStateStore store = new InMemoryIndexStateStore();
        IndexingLifecycleService lifecycle = lifecycle(store, scopedExecutor(root, appArtifact, libArtifact, false));
        UUID projectId = UUID.randomUUID();

        IndexingRun first = lifecycle.execute(projectId, root, discovery(root), negotiation(false));
        Files.writeString(root.resolve("ui/app/src/index.ts"), "export const changed = true;");
        IndexingRun second = lifecycle.execute(projectId, root, discovery(root), negotiation(false));

        assertNotEquals(checkpoint(first, 0).scopeFingerprint(), checkpoint(second, 0).scopeFingerprint());
        assertEquals(checkpoint(first, 1).scopeFingerprint(), checkpoint(second, 1).scopeFingerprint());
    }

    @Test
    void checkpointsCompletedBeforeAProviderFailureSurviveInTheFailedRun(@TempDir Path temp) throws Exception {
        Path root = project(temp);
        Path appArtifact = Files.writeString(temp.resolve("app.scip"), "app");
        Path libArtifact = Files.writeString(temp.resolve("lib.scip"), "lib");
        InMemoryIndexStateStore store = new InMemoryIndexStateStore();
        IndexingLifecycleService lifecycle = lifecycle(store, scopedExecutor(root, appArtifact, libArtifact, true));

        IndexingRun run = lifecycle.execute(UUID.randomUUID(), root, discovery(root), negotiation(false));

        assertEquals(IndexingRun.Status.FAILED, run.status());
        assertEquals(1, run.executions().size());
        assertEquals(Path.of("ui/app"), checkpoint(run, 0).projectRelativeRoot());
        assertEquals(run.executions(), store.findRun(run.id()).orElseThrow().executions());
    }

    @Test
    void incrementalCheckpointCarriesTheModeAndTheScopedChangedFiles(@TempDir Path temp) throws Exception {
        Path root = project(temp);
        Path artifact = Files.writeString(temp.resolve("root.scip"), "root");
        UUID projectId = UUID.randomUUID();
        InMemoryIndexStateStore store = new InMemoryIndexStateStore();
        store.saveProjectState(new ProjectIndexState(projectId, ProjectIndexState.Availability.READY,
                Optional.of("snapshot-old"), Optional.empty(), Instant.EPOCH, Optional.of("baseline")));
        IndexingLifecycleService lifecycle = lifecycle(store, rootExecutor(artifact));
        IndexerNegotiationResult negotiation = negotiation(true);
        IncrementalIndexingPlan plan = new IncrementalIndexingPlanner().plan(partial(projectId), negotiation);

        IndexingRun run = lifecycle.executePlanned(projectId, root, negotiation, plan).orElseThrow();

        assertEquals(IndexingRun.Status.SUCCEEDED, run.status());
        ExecutionCheckpoint checkpoint = checkpoint(run, 0);
        assertEquals(IndexingMode.INCREMENTAL, checkpoint.mode());
        assertEquals(List.of("ui/app/src/index.ts"), checkpoint.changedFiles());
        assertEquals(Path.of(""), checkpoint.projectRelativeRoot());
        assertEquals(new ProjectFingerprintService().capture(root).projectSha256(), checkpoint.scopeFingerprint());
    }

    @Test
    void matchingDigestSidecarIsAcceptedAndMismatchingSidecarWithholdsTheCheckpoint(@TempDir Path temp)
            throws Exception {
        Path root = project(temp);
        Path appArtifact = Files.writeString(temp.resolve("app.scip"), "app");
        Path libArtifact = Files.writeString(temp.resolve("lib.scip"), "lib");
        Files.writeString(temp.resolve("app.scip.sha256"), sha256("app".getBytes(StandardCharsets.UTF_8)) + "  app.scip\n");
        Files.writeString(temp.resolve("lib.scip.sha256"), "f".repeat(64) + "  lib.scip\n");
        InMemoryIndexStateStore store = new InMemoryIndexStateStore();
        IndexingLifecycleService lifecycle = lifecycle(store, scopedExecutor(root, appArtifact, libArtifact, false));

        IndexingRun run = lifecycle.execute(UUID.randomUUID(), root, discovery(root), negotiation(false));

        assertEquals(IndexingRun.Status.SUCCEEDED, run.status(), "lot 1 must not change run outcome");
        assertTrue(run.executions().get(0).checkpoint().isPresent(), "a matching sidecar keeps the checkpoint");
        assertTrue(run.executions().get(1).checkpoint().isEmpty(),
                "a sidecar that disagrees with the artifact bytes must never yield a resumable checkpoint");
        assertTrue(run.message().orElseThrow().contains("checkpoint"), run.message().orElse(""));
    }

    private static ExecutionCheckpoint checkpoint(IndexingRun run, int index) {
        IndexerExecution execution = run.executions().get(index);
        return execution.checkpoint().orElseThrow(() -> new AssertionError("missing checkpoint for " + execution));
    }

    private static Path project(Path temp) throws Exception {
        Path root = Files.createDirectories(temp.resolve("project"));
        Files.createDirectories(root.resolve("ui/app/src"));
        Files.createDirectories(root.resolve("ui/lib/src"));
        Files.writeString(root.resolve("ui/app/src/index.ts"), "export const app = 1;");
        Files.writeString(root.resolve("ui/lib/src/index.ts"), "export const lib = 2;");
        Files.writeString(root.resolve("package.json"), "{}");
        return root;
    }

    private static IndexingLifecycleService lifecycle(InMemoryIndexStateStore store, IndexerExecutor executor) {
        return new IndexingLifecycleService(List.of(executor), request -> "snapshot-" + request.runId(),
                (projectId, runId, snapshotId) -> { }, store, CLOCK);
    }

    private static IndexerExecutor scopedExecutor(Path root, Path appArtifact, Path libArtifact, boolean failLib) {
        return new IndexerExecutor() {
            @Override public String indexerId() { return PROVIDER; }
            @Override public IndexingArtifact execute(IndexingExecutionRequest request) {
                if (failLib && request.projectRelativeRoot().equals(Path.of("ui/lib"))) {
                    throw new IllegalStateException("controlled nested provider failure");
                }
                Path artifact = request.projectRelativeRoot().equals(Path.of("ui/app")) ? appArtifact : libArtifact;
                return new IndexingArtifact(Language.TYPESCRIPT, PROVIDER, artifact, request.projectRelativeRoot());
            }
        };
    }

    private static IndexerExecutor rootExecutor(Path artifact) {
        return new IndexerExecutor() {
            @Override public String indexerId() { return PROVIDER; }
            @Override public IndexingArtifact execute(IndexingExecutionRequest request) {
                return new IndexingArtifact(Language.TYPESCRIPT, PROVIDER, artifact);
            }
        };
    }

    private static ProjectDiscovery discovery(Path root) {
        return new ProjectDiscovery(root, "polyglot", Set.of(Language.TYPESCRIPT), Set.of(BuildSystem.NPM), List.of(
                new DiscoveredModule(Path.of("ui/app"), "app", EnumSet.of(BuildSystem.NPM),
                        List.of(new SourceRoot(Path.of("ui/app/src"), SourceRootKind.SOURCE, Language.TYPESCRIPT))),
                new DiscoveredModule(Path.of("ui/lib"), "lib", EnumSet.of(BuildSystem.NPM),
                        List.of(new SourceRoot(Path.of("ui/lib/src"), SourceRootKind.SOURCE, Language.TYPESCRIPT)))));
    }

    private static IndexerNegotiationResult negotiation(boolean incremental) {
        EnumSet<IndexerCapability> capabilities = EnumSet.of(IndexerCapability.SYMBOLS, IndexerCapability.REFERENCES);
        if (incremental) capabilities.add(IndexerCapability.INCREMENTAL_INDEXING);
        IndexerDescriptor descriptor = new IndexerDescriptor(PROVIDER, PROVIDER_VERSION, PROVIDER,
                Set.of(Language.TYPESCRIPT), Set.of(), capabilities, IndexerQualification.QUALIFIED, 100, List.of());
        return new IndexerNegotiationResult(List.of(new IndexerSelection(Language.TYPESCRIPT, descriptor)),
                Set.of(), List.of());
    }

    private static ProjectInvalidationAssessment partial(UUID projectId) {
        String a = "a".repeat(64);
        String b = "b".repeat(64);
        String c = "c".repeat(64);
        ProjectChangeSet changeSet = new ProjectChangeSet(a, b, c, c, true, false,
                List.of(), List.of("ui/app/src/index.ts"), List.of(), List.of());
        return new ProjectInvalidationAssessment(projectId, Optional.of("snapshot-old"), Optional.of("snapshot-old"),
                ProjectInvalidationScope.PARTIAL_CANDIDATE, List.of(ProjectInvalidationReason.SOURCE_OR_TEST_CHANGED),
                Optional.of(changeSet), List.of("ui/app/src/index.ts"), List.of(), List.of());
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
