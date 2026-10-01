package com.minos.orchestration;

import com.minos.discovery.ProjectDiscovery;
import com.minos.discovery.ProjectDiscovery.BuildSystem;
import com.minos.discovery.ProjectDiscovery.DiscoveredModule;
import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.discovery.ProjectDiscovery.SourceRoot;
import com.minos.discovery.ProjectDiscovery.SourceRootKind;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;
import com.minos.orchestration.IndexingRun.ResumeTrace;
import com.minos.orchestration.IndexingRuntimePorts.ActiveSnapshotObservation;
import com.minos.orchestration.IndexingRuntimePorts.IndexSnapshotStageRequest;
import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotPromoter;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotStager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R1 lot 3 : reprise des providers d'un run INTERRUPTED (ADR 0039 §3). */
class IndexingResumeTest {

    private static final String PROVIDER = "scip-typescript";
    private static final String VERSION = "0.4.0";
    private static final Instant T0 = Instant.parse("2026-09-26T08:00:00Z");
    private static final List<Path> SCOPES = List.of(Path.of("ui/app"), Path.of("ui/lib"), Path.of("ui/web"));

    @Test
    void resumeReexecutesOnlyTheMissingTargetsOnTheSameRunAndStagesTheSameBytesAsAFullRun(@TempDir Path temp)
            throws Exception {
        Fixture fixture = new Fixture(temp);
        IndexingRun full = fixture.lifecycle(new Fixture.Executor(fixture, 99), T0).execute(
                fixture.projectId, fixture.root, fixture.discovery(), negotiation());
        assertEquals(IndexingRun.Status.SUCCEEDED, full.status());
        List<String> fullBytes = fixture.stager.lastArtifacts();
        fixture.resetProjectState();

        IndexingRun interrupted = fixture.interruptAfter(2, T0.plusSeconds(60));
        Fixture.Executor second = new Fixture.Executor(fixture, 99);
        IndexingRun resumed = fixture.lifecycle(second, T0.plusSeconds(600)).execute(
                fixture.projectId, fixture.root, fixture.discovery(), negotiation());

        assertEquals(IndexingRun.Status.SUCCEEDED, resumed.status());
        assertEquals(interrupted.id(), resumed.id(), "resume reopens the same run id");
        assertEquals(List.of(Path.of("ui/web")), second.executed, "only the missing target is re-executed");
        assertEquals(fullBytes, fixture.stager.lastArtifacts(), "staged artifacts are byte-for-byte those of a full run");
        ResumeTrace trace = resumed.resume().orElseThrow();
        assertEquals(2, trace.attempt());
        assertEquals(2, trace.reusedTargets());
        assertEquals(1, trace.reexecutedTargets());
        assertTrue(trace.refusalReason().isEmpty());
        assertEquals(3, resumed.executions().size());
        assertTrue(resumed.executions().stream().allMatch(execution -> execution.checkpoint().isPresent()));
        assertTrue(fixture.markers.unmarked.contains(interrupted.id()), "marker removed once the run is terminal");
        assertEquals(Optional.empty(), fixture.store.findProjectState(fixture.projectId).orElseThrow().resumableRunId());
        assertEquals(IndexingRun.CURRENT_FORMAT_VERSION, fixture.store.findRun(resumed.id()).orElseThrow().runFormatVersion());
    }

    @Test
    void truncatedArtifactRefusesTheWholeResumeAndFallsBackToAFullRun(@TempDir Path temp) throws Exception {
        Fixture fixture = new Fixture(temp);
        IndexingRun interrupted = fixture.interruptAfter(2, T0);
        Path artifact = interrupted.executions().getFirst().finalArtifact();
        Files.write(artifact, "x".getBytes(StandardCharsets.UTF_8));
        Fixture.Executor executor = new Fixture.Executor(fixture, 99);

        IndexingRun run = fixture.lifecycle(executor, T0.plusSeconds(10)).execute(
                fixture.projectId, fixture.root, fixture.discovery(), negotiation());

        assertEquals(IndexingRun.Status.SUCCEEDED, run.status());
        assertNotEquals(interrupted.id(), run.id(), "a refused resume runs fully under a new run id");
        assertEquals(SCOPES, executor.executed);
        ResumeTrace trace = run.resume().orElseThrow();
        assertEquals(0, trace.reusedTargets());
        assertTrue(trace.refusalReason().orElseThrow().contains("size"), trace.refusalReason().orElse(""));
        assertEquals(IndexingRun.Status.FAILED, fixture.store.findRun(interrupted.id()).orElseThrow().status());
    }

    @Test
    void artifactReplacedBetweenPlanningAndStagingAbortsTheResumeAndRunsFully(@TempDir Path temp) throws Exception {
        Fixture fixture = new Fixture(temp);
        IndexingRun interrupted = fixture.interruptAfter(2, T0);
        Path reused = interrupted.executions().getFirst().finalArtifact();
        Fixture.Executor executor = new Fixture.Executor(fixture, 99) {
            @Override public IndexingArtifact execute(IndexingExecutionRequest request) throws Exception {
                if (request.projectRelativeRoot().equals(Path.of("ui/web")) && executed.isEmpty()) {
                    // Same size, different bytes: only the digest re-verification can catch it.
                    Files.writeString(reused, "T".repeat((int) Files.size(reused)));
                }
                return super.execute(request);
            }
        };

        IndexingRun run = fixture.lifecycle(executor, T0.plusSeconds(10)).execute(
                fixture.projectId, fixture.root, fixture.discovery(), negotiation());

        assertEquals(IndexingRun.Status.SUCCEEDED, run.status());
        assertNotEquals(interrupted.id(), run.id());
        IndexingRun aborted = fixture.store.findRun(interrupted.id()).orElseThrow();
        assertEquals(IndexingRun.Status.FAILED, aborted.status());
        assertTrue(aborted.message().orElseThrow().contains("resume aborted"), aborted.message().orElse(""));
        assertTrue(run.resume().orElseThrow().refusalReason().orElseThrow().contains("resume aborted"));
        assertEquals(List.of(Path.of("ui/web"), Path.of("ui/app"), Path.of("ui/lib"), Path.of("ui/web")),
                executor.executed, "the aborted attempt executed the missing target, then the full run everything");
    }

    @Test
    void fileModifiedInOneScopeReexecutesOnlyThatScope(@TempDir Path temp) throws Exception {
        Fixture fixture = new Fixture(temp);
        IndexingRun interrupted = fixture.interruptAfter(2, T0);
        Files.writeString(fixture.root.resolve("ui/app/src/index.ts"), "export const changed = true;");
        Fixture.Executor executor = new Fixture.Executor(fixture, 99);

        IndexingRun run = fixture.lifecycle(executor, T0.plusSeconds(10)).execute(
                fixture.projectId, fixture.root, fixture.discovery(), negotiation());

        assertEquals(IndexingRun.Status.SUCCEEDED, run.status());
        assertEquals(interrupted.id(), run.id());
        assertEquals(List.of(Path.of("ui/app"), Path.of("ui/web")), executor.executed);
        assertEquals(1, run.resume().orElseThrow().reusedTargets());
        assertEquals(2, run.resume().orElseThrow().reexecutedTargets());
    }

    @Test
    void changedProviderVersionReusesNothingAndRunsFully(@TempDir Path temp) throws Exception {
        Fixture fixture = new Fixture(temp);
        IndexingRun interrupted = fixture.interruptAfter(2, T0);
        Fixture.Executor executor = new Fixture.Executor(fixture, 99);

        IndexingRun run = fixture.lifecycle(executor, T0.plusSeconds(10)).execute(
                fixture.projectId, fixture.root, fixture.discovery(), negotiation("0.5.0", PROVIDER));

        assertNotEquals(interrupted.id(), run.id());
        assertEquals(SCOPES, executor.executed);
        assertTrue(run.resume().orElseThrow().refusalReason().orElseThrow().contains("no reusable target"));
    }

    @Test
    void deletedRunDirectoryRefusesTheResume(@TempDir Path temp) throws Exception {
        Fixture fixture = new Fixture(temp);
        IndexingRun interrupted = fixture.interruptAfter(2, T0);
        deleteRecursively(fixture.markers.runDirectory(interrupted.id()).orElseThrow());
        Fixture.Executor executor = new Fixture.Executor(fixture, 99);

        IndexingRun run = fixture.lifecycle(executor, T0.plusSeconds(10)).execute(
                fixture.projectId, fixture.root, fixture.discovery(), negotiation());

        assertNotEquals(interrupted.id(), run.id());
        assertEquals(IndexingRun.Status.SUCCEEDED, run.status());
        assertTrue(run.resume().orElseThrow().refusalReason().orElseThrow().contains("run directory"));
    }

    @Test
    void expiredCheckpointsRefuseTheResume(@TempDir Path temp) throws Exception {
        Fixture fixture = new Fixture(temp);
        IndexingRun interrupted = fixture.interruptAfter(2, T0);
        Fixture.Executor executor = new Fixture.Executor(fixture, 99);

        IndexingRun run = fixture.lifecycle(executor, T0.plus(Duration.ofHours(25))).execute(
                fixture.projectId, fixture.root, fixture.discovery(), negotiation());

        assertNotEquals(interrupted.id(), run.id());
        assertEquals(SCOPES, executor.executed);
        assertTrue(run.resume().orElseThrow().refusalReason().orElseThrow().contains("TTL"));
    }

    @Test
    void indexerWithoutResumableArtifactQualificationIsNeverReused(@TempDir Path temp) throws Exception {
        Fixture fixture = new Fixture(temp);
        IndexingRun interrupted = fixture.interruptAfter(2, T0);
        Fixture.Executor executor = new Fixture.Executor(fixture, 99);

        // The double now negotiates without RESUMABLE_ARTIFACT: the checkpoints exist but are never reused.
        IndexingRun run = fixture.lifecycle(executor, T0.plusSeconds(10)).execute(
                fixture.projectId, fixture.root, fixture.discovery(), negotiation(VERSION, PROVIDER, false));

        assertNotEquals(interrupted.id(), run.id());
        assertEquals(SCOPES, executor.executed);
        assertTrue(run.resume().orElseThrow().refusalReason().orElseThrow().contains("no reusable target"));
    }

    @Test
    void noResumePolicySupersedesTheInterruptedRunAndRunsFully(@TempDir Path temp) throws Exception {
        Fixture fixture = new Fixture(temp);
        IndexingRun interrupted = fixture.interruptAfter(2, T0);
        Fixture.Executor executor = new Fixture.Executor(fixture, 99);

        IndexingRun run = fixture.lifecycle(executor, T0.plusSeconds(10)).execute(
                fixture.projectId, fixture.root, fixture.discovery(), negotiation(), IndexingResumePolicy.NO_RESUME);

        assertNotEquals(interrupted.id(), run.id());
        assertEquals(SCOPES, executor.executed);
        assertTrue(run.resume().isEmpty(), "no resume was considered");
        IndexingRun superseded = fixture.store.findRun(interrupted.id()).orElseThrow();
        assertEquals(IndexingRun.Status.FAILED, superseded.status());
        assertTrue(superseded.message().orElseThrow().contains("superseded"));
    }

    @Test
    void resumeOnlyPolicyFailsCleanlyWhenNothingCanBeResumed(@TempDir Path temp) throws Exception {
        Fixture fixture = new Fixture(temp);
        Fixture.Executor executor = new Fixture.Executor(fixture, 99);
        IndexingLifecycleService lifecycle = fixture.lifecycle(executor, T0);

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> lifecycle.execute(
                fixture.projectId, fixture.root, fixture.discovery(), negotiation(), IndexingResumePolicy.RESUME_ONLY));

        assertTrue(failure.getMessage().contains("resume"), failure.getMessage());
        assertTrue(executor.executed.isEmpty());
        assertTrue(fixture.store.listRuns(fixture.projectId).isEmpty(), "no run is created");

        IndexingRun interrupted = fixture.interruptAfter(2, T0);
        Files.write(interrupted.executions().getFirst().finalArtifact(), new byte[] {1});
        assertThrows(IllegalStateException.class, () -> lifecycle.execute(
                fixture.projectId, fixture.root, fixture.discovery(), negotiation(), IndexingResumePolicy.RESUME_ONLY));
        assertEquals(1, fixture.store.listRuns(fixture.projectId).size(), "a refused resume-only creates no run");
        assertEquals(IndexingRun.Status.INTERRUPTED, fixture.store.findRun(interrupted.id()).orElseThrow().status());
    }

    @Test
    void secondInterruptionKeepsTheReusedCheckpointsAndIncrementsTheAttempt(@TempDir Path temp) throws Exception {
        Fixture fixture = new Fixture(temp);
        IndexingRun interrupted = fixture.interruptAfter(2, T0);
        // Simulate a second crash: the resumed attempt dies after re-executing nothing.
        Fixture.Executor crashing = new Fixture.Executor(fixture, 0);
        IndexingRun failed = fixture.lifecycle(crashing, T0.plusSeconds(10)).execute(
                fixture.projectId, fixture.root, fixture.discovery(), negotiation());
        assertEquals(interrupted.id(), failed.id());
        assertEquals(IndexingRun.Status.FAILED, failed.status());
        IndexingRun asRunning = fixture.reopenAsRunning(failed);
        assertEquals(2, asRunning.executions().size(), "reused checkpoints were persisted before the crash");

        Fixture.Executor executor = new Fixture.Executor(fixture, 99);
        IndexingRun resumed = fixture.lifecycle(executor, T0.plusSeconds(20)).execute(
                fixture.projectId, fixture.root, fixture.discovery(), negotiation());

        assertEquals(IndexingRun.Status.SUCCEEDED, resumed.status());
        assertEquals(interrupted.id(), resumed.id());
        assertEquals(3, resumed.resume().orElseThrow().attempt());
        assertEquals(List.of(Path.of("ui/web")), executor.executed);
    }

    @Test
    void interruptionDuringStagingRelaunchesNoProviderAndRestagesTheSameBytes(@TempDir Path temp) throws Exception {
        Fixture fixture = new Fixture(temp);
        IndexingRun full = fixture.lifecycle(new Fixture.Executor(fixture, 99), T0).execute(
                fixture.projectId, fixture.root, fixture.discovery(), negotiation());
        List<String> fullBytes = fixture.stager.lastArtifacts();
        fixture.resetProjectState();
        IndexingRun interrupted = fixture.interruptInPhase(IndexingRun.Phase.STAGING, Optional.empty(), T0.plusSeconds(60));
        Fixture.Executor executor = new Fixture.Executor(fixture, 0);
        int stagedBefore = fixture.stager.requests.size();

        IndexingRun resumed = fixture.lifecycle(executor, T0.plusSeconds(600)).execute(
                fixture.projectId, fixture.root, fixture.discovery(), negotiation());

        assertEquals(IndexingRun.Status.SUCCEEDED, resumed.status());
        assertEquals(interrupted.id(), resumed.id());
        assertTrue(executor.executed.isEmpty(), "no provider is relaunched after a staging interruption");
        assertEquals(stagedBefore + 1, fixture.stager.requests.size(), "the snapshot is staged again");
        assertEquals(fullBytes, fixture.stager.lastArtifacts());
        assertEquals(3, resumed.resume().orElseThrow().reusedTargets());
        assertEquals(0, resumed.resume().orElseThrow().reexecutedTargets());
        assertNotEquals(full.id(), resumed.id());
    }

    @Test
    void interruptionDuringStagingStillReverifiesEveryReusedArtifact(@TempDir Path temp) throws Exception {
        Fixture fixture = new Fixture(temp);
        IndexingRun interrupted = fixture.interruptInPhase(IndexingRun.Phase.STAGING, Optional.empty(), T0);
        Path reused = interrupted.executions().get(1).finalArtifact();
        Fixture.Executor executor = new Fixture.Executor(fixture, 99);
        // Same size, different bytes, written after the planner would have verified it: the planner
        // and the pre-staging check run in the same call, so the tampering happens before both and
        // the planner refuses; a tampering between them is covered by the swap-for-a-link test below.
        // Here the resume is aborted before staging because no provider runs in between.
        Files.writeString(reused, "T".repeat((int) Files.size(reused)));

        IndexingRun run = fixture.lifecycle(executor, T0.plusSeconds(10)).execute(
                fixture.projectId, fixture.root, fixture.discovery(), negotiation());

        assertEquals(IndexingRun.Status.SUCCEEDED, run.status());
        assertNotEquals(interrupted.id(), run.id());
        assertEquals(SCOPES, executor.executed, "the full run re-executes everything");
        assertTrue(run.resume().orElseThrow().refusalReason().orElseThrow().contains("digest"));
    }

    @Test
    void reusedArtifactSwappedForALinkOutsideTheRunDirectoryAbortsTheResume(@TempDir Path temp) throws Exception {
        Fixture fixture = new Fixture(temp);
        IndexingRun interrupted = fixture.interruptAfter(2, T0);
        Path reused = interrupted.executions().getFirst().finalArtifact();
        Path outside = Files.copy(reused, temp.resolve("outside-copy.scip"));
        Fixture.Executor executor = new Fixture.Executor(fixture, 99) {
            @Override public IndexingArtifact execute(IndexingExecutionRequest request) throws Exception {
                if (executed.isEmpty()) {
                    // V16: identical bytes, but the pathname now leaves runs/<runId>/ through a link.
                    Files.delete(reused);
                    try {
                        Files.createSymbolicLink(reused, outside);
                    } catch (IOException | UnsupportedOperationException | SecurityException unsupported) {
                        Files.copy(outside, reused);
                        throw new org.opentest4j.TestAbortedException("symbolic links are not creatable on this host");
                    }
                }
                return super.execute(request);
            }
        };

        IndexingRun run;
        try {
            run = fixture.lifecycle(executor, T0.plusSeconds(10)).execute(
                    fixture.projectId, fixture.root, fixture.discovery(), negotiation());
        } catch (org.opentest4j.TestAbortedException aborted) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, aborted.getMessage());
            return;
        }

        assertNotEquals(interrupted.id(), run.id());
        IndexingRun aborted = fixture.store.findRun(interrupted.id()).orElseThrow();
        assertEquals(IndexingRun.Status.FAILED, aborted.status());
        assertTrue(aborted.message().orElseThrow().contains("resume aborted"), aborted.message().orElse(""));
    }

    @Test
    void interruptionBetweenStageAndPromotePromotesTheKnownStagedSnapshotDirectly(@TempDir Path temp) throws Exception {
        Fixture fixture = new Fixture(temp);
        fixture.active.set("snapshot-old");
        IndexingRun interrupted = fixture.interruptInPhase(IndexingRun.Phase.PROMOTION, Optional.of("snapshot-staged"), T0);
        Fixture.Executor executor = new Fixture.Executor(fixture, 0);
        int stagedBefore = fixture.stager.requests.size();

        IndexingRun resumed = fixture.lifecycle(executor, T0.plusSeconds(10)).execute(
                fixture.projectId, fixture.root, fixture.discovery(), negotiation());

        assertEquals(IndexingRun.Status.SUCCEEDED, resumed.status());
        assertEquals(interrupted.id(), resumed.id());
        assertTrue(executor.executed.isEmpty(), "no provider is relaunched");
        assertEquals(stagedBefore, fixture.stager.requests.size(), "no staging is redone");
        assertEquals(List.of("snapshot-staged"), fixture.promoted, "the known staged snapshot is promoted directly");
        assertEquals(Optional.of("snapshot-staged"), resumed.activeSnapshotAfter());
        assertEquals(Optional.of("snapshot-staged"),
                fixture.store.findProjectState(fixture.projectId).orElseThrow().activeSnapshotId());
    }

    @Test
    void interruptionAfterPromoteIsRecoveredAsSuccessAndOffersNothingToResume(@TempDir Path temp) throws Exception {
        Fixture fixture = new Fixture(temp);
        fixture.active.set("snapshot-staged");
        IndexingRun interrupted = fixture.interruptInPhase(IndexingRun.Phase.PROMOTION, Optional.of("snapshot-staged"), T0);
        Fixture.Executor executor = new Fixture.Executor(fixture, 0);
        IndexingLifecycleService lifecycle = fixture.lifecycle(executor, T0.plusSeconds(10));

        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> lifecycle.execute(
                fixture.projectId, fixture.root, fixture.discovery(), negotiation(), IndexingResumePolicy.RESUME_ONLY));

        assertTrue(refused.getMessage().contains("no interrupted run"), refused.getMessage());
        IndexingRun recovered = fixture.store.findRun(interrupted.id()).orElseThrow();
        assertEquals(IndexingRun.Status.SUCCEEDED, recovered.status());
        assertEquals(Optional.of("snapshot-staged"), recovered.activeSnapshotAfter());
        assertTrue(executor.executed.isEmpty());
        assertTrue(fixture.promoted.isEmpty(), "an already authoritative snapshot is not promoted again");
        assertEquals(ProjectIndexState.Availability.READY,
                fixture.store.findProjectState(fixture.projectId).orElseThrow().availability());
    }

    @Test
    void stagedSnapshotThatCanNoLongerBePromotedAbortsTheResumeAndRunsFully(@TempDir Path temp) throws Exception {
        Fixture fixture = new Fixture(temp);
        fixture.active.set("snapshot-old");
        fixture.rejectPromotionOf = "snapshot-gone";
        IndexingRun interrupted = fixture.interruptInPhase(IndexingRun.Phase.PROMOTION, Optional.of("snapshot-gone"), T0);
        Fixture.Executor executor = new Fixture.Executor(fixture, 99);

        IndexingRun run = fixture.lifecycle(executor, T0.plusSeconds(10)).execute(
                fixture.projectId, fixture.root, fixture.discovery(), negotiation());

        assertEquals(IndexingRun.Status.SUCCEEDED, run.status());
        assertNotEquals(interrupted.id(), run.id());
        assertEquals(SCOPES, executor.executed, "the fallback is a full run");
        IndexingRun aborted = fixture.store.findRun(interrupted.id()).orElseThrow();
        assertEquals(IndexingRun.Status.FAILED, aborted.status());
        assertTrue(aborted.message().orElseThrow().contains("resume aborted"), aborted.message().orElse(""));
        assertTrue(run.resume().orElseThrow().refusalReason().orElseThrow().contains("promoted"));
    }

    @Test
    void stagedSnapshotIsNeverPromotedWhenAModuleWasRemovedSinceTheInterruption(@TempDir Path temp) throws Exception {
        // R4: the run was interrupted in PROMOTION over three scopes; the user then deletes one module.
        // Every remaining target still owns a valid checkpoint, but the staged snapshot also indexes the
        // removed module: promoting it would publish an index a full run would never produce.
        Fixture fixture = new Fixture(temp);
        fixture.active.set("snapshot-old");
        IndexingRun interrupted = fixture.interruptInPhase(IndexingRun.Phase.PROMOTION, Optional.of("snapshot-staged"), T0);
        deleteRecursively(fixture.root.resolve("ui/web"));
        Fixture.Executor executor = new Fixture.Executor(fixture, 0);
        int stagedBefore = fixture.stager.requests.size();

        IndexingRun resumed = fixture.lifecycle(executor, T0.plusSeconds(10)).execute(
                fixture.projectId, fixture.root, fixture.discovery(List.of(Path.of("ui/app"), Path.of("ui/lib"))),
                negotiation());

        assertEquals(IndexingRun.Status.SUCCEEDED, resumed.status());
        assertEquals(interrupted.id(), resumed.id(), "the run is still resumed: both remaining targets are reusable");
        assertTrue(executor.executed.isEmpty(), "no provider is relaunched");
        assertFalse(fixture.promoted.contains("snapshot-staged"), "the stale staged snapshot must never be promoted");
        assertEquals(stagedBefore + 1, fixture.stager.requests.size(), "the snapshot is prepared again");
        assertEquals(2, fixture.stager.lastArtifacts().size(), "and it holds the two current scopes only");
        assertTrue(fixture.stager.lastArtifacts().stream().noneMatch(artifact -> artifact.startsWith("ui/web=")));
        assertNotEquals(Optional.of("snapshot-staged"), resumed.activeSnapshotAfter());
        assertEquals(resumed.activeSnapshotAfter(),
                fixture.store.findProjectState(fixture.projectId).orElseThrow().activeSnapshotId());
    }

    @Test
    void stagedSnapshotOfAnExecutionWithoutCheckpointIsNotProvablyCurrentAndIsPreparedAgain(@TempDir Path temp)
            throws Exception {
        // R4, fail-closed: an execution whose checkpoint was withheld cannot be tied to any current
        // target key, so the staged snapshot that contains it cannot be proven to match the plan.
        Fixture fixture = new Fixture(temp);
        fixture.active.set("snapshot-old");
        IndexingRun interrupted = fixture.interruptInPhase(IndexingRun.Phase.PROMOTION, Optional.of("snapshot-staged"), T0);
        List<IndexingRun.IndexerExecution> executions = new ArrayList<>(interrupted.executions());
        executions.add(new IndexingRun.IndexerExecution(
                com.minos.discovery.ProjectDiscovery.Language.TYPESCRIPT, PROVIDER, Path.of("removed/index.scip")));
        fixture.store.saveRun(new IndexingRun(interrupted.id(), fixture.projectId, interrupted.status(),
                interrupted.phase(), interrupted.createdAt(), Optional.empty(), executions,
                interrupted.stagedSnapshotId(), interrupted.activeSnapshotBefore(), interrupted.activeSnapshotAfter(),
                interrupted.message(), interrupted.runFormatVersion(), interrupted.resume()));
        Fixture.Executor executor = new Fixture.Executor(fixture, 0);

        IndexingRun resumed = fixture.lifecycle(executor, T0.plusSeconds(10)).execute(
                fixture.projectId, fixture.root, fixture.discovery(), negotiation());

        assertEquals(IndexingRun.Status.SUCCEEDED, resumed.status());
        assertFalse(fixture.promoted.contains("snapshot-staged"));
        assertEquals(3, fixture.stager.lastArtifacts().size());
    }

    static IndexerNegotiationResult negotiation() {
        return negotiation(VERSION, PROVIDER);
    }

    static IndexerNegotiationResult negotiation(String version, String id) {
        return negotiation(version, id, true);
    }

    static IndexerNegotiationResult negotiation(String version, String id, boolean resumableArtifact) {
        EnumSet<IndexerCapability> capabilities = EnumSet.of(IndexerCapability.SYMBOLS, IndexerCapability.REFERENCES);
        if (resumableArtifact) capabilities.add(IndexerCapability.RESUMABLE_ARTIFACT);
        IndexerDescriptor descriptor = new IndexerDescriptor(id, version, id, Set.of(Language.TYPESCRIPT), Set.of(),
                capabilities, IndexerQualification.QUALIFIED, 100, List.of());
        return new IndexerNegotiationResult(List.of(new IndexerSelection(Language.TYPESCRIPT, descriptor)),
                Set.of(), List.of());
    }

    static void deleteRecursively(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    /** Project with three TypeScript modules, a MINOS home with a run directory layout and fakes. */
    static final class Fixture {
        final Path root;
        final Path home;
        final UUID projectId = UUID.randomUUID();
        final InMemoryIndexStateStore store = new InMemoryIndexStateStore();
        final RecordingStager stager = new RecordingStager();
        final AtomicReference<String> active = new AtomicReference<>();
        final List<String> promoted = new ArrayList<>();
        String rejectPromotionOf;
        final SnapshotPromoter promoter = new SnapshotPromoter() {
            @Override public void promote(UUID id, UUID runId, String staged) throws IOException {
                if (staged.equals(rejectPromotionOf)) throw new IOException("staged snapshot is missing");
                promoted.add(staged);
                active.set(staged);
            }
            @Override public ActiveSnapshotObservation observeActiveSnapshot(UUID id) {
                return active.get() == null
                        ? ActiveSnapshotObservation.noActiveSnapshot() : ActiveSnapshotObservation.active(active.get());
            }
        };
        final RunDirectoryMarkers markers;

        Fixture(Path temp) throws IOException {
            root = Files.createDirectories(temp.resolve("project"));
            home = Files.createDirectories(temp.resolve("home"));
            for (Path scope : SCOPES) {
                Files.createDirectories(root.resolve(scope).resolve("src"));
                Files.writeString(root.resolve(scope).resolve("src/index.ts"), "export const v = '" + scope + "';");
            }
            Files.writeString(root.resolve("package.json"), "{}");
            markers = new RunDirectoryMarkers(home.resolve("runs"));
        }

        IndexingLifecycleService lifecycle(IndexerExecutor executor, Instant now) {
            return new IndexingLifecycleService(List.of(executor), stager, promoter, store, markers,
                    ResumableArtifactPolicy.DEFAULT, Clock.fixed(now, ZoneOffset.UTC));
        }

        ProjectDiscovery discovery() {
            return discovery(SCOPES);
        }

        ProjectDiscovery discovery(List<Path> scopes) {
            List<DiscoveredModule> modules = new ArrayList<>();
            for (Path scope : scopes) {
                modules.add(new DiscoveredModule(scope, scope.getFileName().toString(), EnumSet.of(BuildSystem.NPM),
                        List.of(new SourceRoot(scope.resolve("src"), SourceRootKind.SOURCE, Language.TYPESCRIPT))));
            }
            return new ProjectDiscovery(root, "polyglot", Set.of(Language.TYPESCRIPT), Set.of(BuildSystem.NPM), modules);
        }

        /** Runs providers that fail after {@code completed} targets, then rewrites the trace as a crash. */
        IndexingRun interruptAfter(int completed, Instant now) {
            IndexingRun failed = lifecycle(new Executor(this, completed), now).execute(
                    projectId, root, discovery(), negotiation());
            assertEquals(IndexingRun.Status.FAILED, failed.status());
            assertEquals(completed, failed.executions().size());
            return reopenAsRunning(failed);
        }

        /** A full provider phase, then a crash in {@code phase} (STAGING or PROMOTION) with every checkpoint. */
        IndexingRun interruptInPhase(IndexingRun.Phase phase, Optional<String> staged, Instant now) {
            String activeBefore = active.get();
            IndexingRun completed = lifecycle(new Executor(this, 99), now).execute(
                    projectId, root, discovery(), negotiation());
            assertEquals(IndexingRun.Status.SUCCEEDED, completed.status());
            active.set(activeBefore);
            promoted.clear();
            IndexingRun running = new IndexingRun(completed.id(), projectId, IndexingRun.Status.RUNNING, phase,
                    completed.createdAt(), Optional.empty(), completed.executions(), staged,
                    Optional.ofNullable(activeBefore), Optional.ofNullable(activeBefore),
                    Optional.of("in progress"), completed.runFormatVersion(), completed.resume());
            store.saveRun(running);
            store.saveProjectState(new ProjectIndexState(projectId,
                    activeBefore != null ? ProjectIndexState.Availability.REFRESHING
                            : ProjectIndexState.Availability.INDEXING,
                    Optional.ofNullable(activeBefore), Optional.of(completed.id()), completed.createdAt(),
                    Optional.of("indexing run in progress")));
            return running;
        }

        /** A crash leaves the run RUNNING with its persisted checkpoints and the project in progress. */
        IndexingRun reopenAsRunning(IndexingRun failed) {
            IndexingRun running = new IndexingRun(failed.id(), projectId, IndexingRun.Status.RUNNING,
                    IndexingRun.Phase.PROVIDER_EXECUTION, failed.createdAt(), Optional.empty(), failed.executions(),
                    Optional.empty(), failed.activeSnapshotBefore(), failed.activeSnapshotBefore(),
                    Optional.of("provider execution in progress"), failed.runFormatVersion(), failed.resume());
            store.saveRun(running);
            store.saveProjectState(new ProjectIndexState(projectId,
                    failed.activeSnapshotBefore().isPresent() ? ProjectIndexState.Availability.REFRESHING
                            : ProjectIndexState.Availability.INDEXING,
                    failed.activeSnapshotBefore(), Optional.of(failed.id()), failed.createdAt(),
                    Optional.of("indexing run in progress")));
            return running;
        }

        void resetProjectState() {
            active.set(null);
            for (IndexingRun run : store.listRuns(projectId)) {
                store.saveRun(new IndexingRun(run.id(), UUID.randomUUID(), run.status(), run.phase(), run.createdAt(),
                        run.completedAt(), run.executions(), run.stagedSnapshotId(), run.activeSnapshotBefore(),
                        run.activeSnapshotAfter(), run.message(), run.runFormatVersion(), run.resume()));
            }
            store.saveProjectState(ProjectIndexState.neverIndexed(projectId, T0));
        }

        /** Writes deterministic artifacts under runs/<runId>/<indexerId>/scopes/<scope>/index.scip. */
        static class Executor implements IndexerExecutor {
            final Fixture fixture;
            final int failAfter;
            final List<Path> executed = new ArrayList<>();

            Executor(Fixture fixture, int failAfter) {
                this.fixture = fixture;
                this.failAfter = failAfter;
            }

            @Override public String indexerId() { return PROVIDER; }

            @Override public IndexingArtifact execute(IndexingExecutionRequest request) throws Exception {
                if (executed.size() >= failAfter) throw new IllegalStateException("simulated provider crash");
                executed.add(request.projectRelativeRoot());
                Path directory = fixture.home.resolve("runs").resolve(request.runId().toString()).resolve(PROVIDER)
                        .resolve("scopes").resolve(request.projectRelativeRoot().toString().replace('/', '-'));
                Files.createDirectories(directory);
                Path artifact = directory.resolve("index.scip");
                String source = Files.readString(request.projectRoot().resolve("src/index.ts"));
                Files.writeString(artifact, "scip:" + request.projectRelativeRoot().toString().replace('\\', '/')
                        + ":" + HexFormat.of().formatHex(source.getBytes(StandardCharsets.UTF_8)));
                return new IndexingArtifact(Language.TYPESCRIPT, PROVIDER, artifact, request.projectRelativeRoot());
            }
        }
    }

    static final class RecordingStager implements SnapshotStager {
        private final List<List<String>> requests = new ArrayList<>();

        @Override public String stage(IndexSnapshotStageRequest request) throws IOException {
            List<String> artifacts = new ArrayList<>();
            for (IndexingArtifact artifact : request.artifacts()) {
                artifacts.add(artifact.projectRelativeRoot().toString().replace('\\', '/') + "=" + artifact.indexerId()
                        + ":" + Files.readString(artifact.finalArtifact()));
            }
            requests.add(artifacts);
            return "snapshot-" + requests.size();
        }

        List<String> lastArtifacts() { return requests.getLast(); }
    }

    /** Port fake: markers recorded in memory, run directories under a real runs root. */
    static final class RunDirectoryMarkers implements ResumableRunMarkers {
        final Path runsRoot;
        final List<UUID> marked = new ArrayList<>();
        final List<UUID> unmarked = new ArrayList<>();

        RunDirectoryMarkers(Path runsRoot) { this.runsRoot = runsRoot; }

        @Override public void mark(UUID runId) { marked.add(runId); }
        @Override public void unmark(UUID runId) { unmarked.add(runId); }
        @Override public Optional<Path> runDirectory(UUID runId) { return Optional.of(runsRoot.resolve(runId.toString())); }
    }
}
