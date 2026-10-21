package com.minos.application.indexing;

import com.minos.incremental.ProjectFingerprint;
import com.minos.incremental.ProjectFingerprintSnapshot;
import com.minos.incremental.ProjectInvalidationReason;
import com.minos.orchestration.IndexingMode;
import com.minos.orchestration.IndexingResumePolicy;
import com.minos.orchestration.IndexingRun;
import com.minos.runtime.ProviderRuntimeStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AUD-ARC-02: the single indexing orchestration, invoked without any class of a surface. It keeps the behaviour of
 * the command it replaces: complete, incremental and unchanged runs, refusals before any run, and retention on the
 * four terminal paths (success, no changes, failed run, fingerprint that cannot be published).
 */
class ProjectIndexingUseCaseTest {

    @TempDir Path temp;

    @Test
    void aCompleteThenAnUnchangedThenAnIncrementalIndexingFollowTheProductPath() throws Exception {
        IndexingFixture fixture = new IndexingFixture(temp);
        ProjectIndexingUseCase useCase = fixture.useCase();

        var first = useCase.execute(fixture.id(), null, false, IndexingResumePolicy.RESUME);
        var unchanged = useCase.execute(fixture.id(), null, false, IndexingResumePolicy.RESUME);
        Files.writeString(fixture.root.resolve("src/main/java/App.java"), "class App { int changed = 1; }");
        var incremental = useCase.execute(fixture.id(), null, false, IndexingResumePolicy.RESUME);

        assertEquals(IndexingMode.FULL, first.preparation().effectiveMode());
        assertEquals("SUCCEEDED", first.status());
        assertEquals(Optional.of("snapshot-1"), first.activeSnapshotId());
        assertTrue(first.fingerprintPromoted());
        assertEquals(Optional.empty(), first.diagnostic());

        assertEquals(IndexingMode.NONE, unchanged.preparation().effectiveMode());
        assertEquals(ProjectIndexingUseCase.Outcome.NO_CHANGES, unchanged.status());
        assertTrue(unchanged.run().isEmpty());
        assertEquals(Optional.of("snapshot-1"), unchanged.activeSnapshotId());

        assertEquals(IndexingMode.INCREMENTAL, incremental.preparation().effectiveMode());
        assertEquals(List.of("src/main/java/App.java"), incremental.preparation().changedFiles());
        assertEquals(IndexingMode.INCREMENTAL, fixture.executor.lastRequest.get().mode());
        assertEquals(List.of("src/main/java/App.java"), fixture.executor.lastRequest.get().changedFiles());
        assertEquals(Optional.of("snapshot-2"), incremental.activeSnapshotId());
        assertEquals("snapshot-2", fixture.fingerprints.loadActive(fixture.project.id()).orElseThrow().indexSnapshotId());

        assertEquals(2, fixture.executor.calls.get());
        assertEquals(3, fixture.compactions.get(), "retention runs after every successful path");
        assertEquals(3, fixture.semanticRefreshes.get());
    }

    @Test
    void aForcedIndexingIsCompleteAndADryRunWritesNoState() throws Exception {
        IndexingFixture fixture = new IndexingFixture(temp);
        ProjectIndexingUseCase useCase = fixture.useCase();

        var dryRun = useCase.plan(fixture.id(), null, true);

        assertEquals(IndexingMode.FULL, dryRun.effectiveMode());
        assertTrue(dryRun.reasons().contains("FORCED_FULL"));
        assertEquals(List.of(IndexingFixture.INDEXER), dryRun.providerIds());
        assertTrue(fixture.stateStore.findProjectState(fixture.project.id()).isEmpty(), "a dry run saves nothing");
        assertEquals(0, fixture.executor.calls.get());

        useCase.execute(fixture.id(), null, false, IndexingResumePolicy.RESUME);
        var forced = useCase.execute(fixture.id(), null, true, IndexingResumePolicy.RESUME);
        var noResume = useCase.execute(fixture.id(), null, false, IndexingResumePolicy.NO_RESUME);

        assertEquals("SUCCEEDED", forced.status());
        assertEquals(List.of(), forced.preparation().changedFiles());
        assertEquals("SUCCEEDED", noResume.status(), "--no-resume indexes everything, it never answers NO_CHANGES");
        assertEquals(3, fixture.executor.calls.get());
    }

    @Test
    void anIncompleteNegotiationIsRefusedBeforeAnyRun() throws Exception {
        IndexingFixture fixture = new IndexingFixture(temp);
        fixture.qualifiedProvider = false;

        ProjectIndexingUseCase useCase = fixture.useCase();
        String projectId = fixture.id();
        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> useCase.execute(projectId, null, false, IndexingResumePolicy.RESUME));

        assertEquals("no qualified provider covers languages: [JAVA]", refusal.getMessage());
        assertEquals(0, fixture.executor.calls.get());
        assertEquals(0, fixture.compactions.get());
    }

    @Test
    void aRuntimeThatIsNotReadyIsRefusedBeforeAnyRun() throws Exception {
        IndexingFixture fixture = new IndexingFixture(temp);
        fixture.runtimeState = ProviderRuntimeStatus.State.NOT_INSTALLED;

        ProjectIndexingUseCase useCase = fixture.useCase();
        String projectId = fixture.id();
        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> useCase.execute(projectId, null, false, IndexingResumePolicy.RESUME));

        assertEquals("provider runtime is not ready: java-indexer — not installed", refusal.getMessage());
        assertEquals(0, fixture.executor.calls.get());
    }

    @Test
    void aResumeOnlyIndexingOfAnUnchangedProjectIsRefused() throws Exception {
        IndexingFixture fixture = new IndexingFixture(temp);
        ProjectIndexingUseCase useCase = fixture.useCase();
        String projectId = fixture.id();
        useCase.execute(fixture.id(), null, false, IndexingResumePolicy.RESUME);

        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> useCase.execute(projectId, null, false, IndexingResumePolicy.RESUME_ONLY));

        assertEquals("resume-only indexing refused: no changes to index", refusal.getMessage());
    }

    @Test
    void aRunThatFailsIsAPublicFailureAndIsCompactedWithoutHidingIt() throws Exception {
        IndexingFixture fixture = new IndexingFixture(temp);
        fixture.executor.failure = new IllegalStateException("provider crashed in " + temp.toAbsolutePath());
        fixture.retentionFailure = new IOException("retention lock timed out");

        ProjectIndexingUseCase useCase = fixture.useCase();
        String projectId = fixture.id();
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> useCase.execute(projectId, null, false, IndexingResumePolicy.RESUME));

        assertTrue(failure.getMessage().startsWith("indexing run "), failure.getMessage());
        assertFalse(failure.getMessage().contains(temp.toAbsolutePath().toString()), failure.getMessage());
        assertEquals(1, fixture.compactions.get());
        assertSame(fixture.retentionFailure, failure.getSuppressed()[0]);
        assertTrue(fixture.fingerprints.loadActive(fixture.project.id()).isEmpty());
    }

    @Test
    void aFingerprintThatCannotBePublishedFailsTheIndexingAndKeepsARetentionFailureSuppressed() throws Exception {
        IndexingFixture fixture = new IndexingFixture(temp);
        fixture.fingerprints = new IndexingFixture.FingerprintStore() {
            @Override
            public ProjectFingerprintSnapshot publish(UUID projectId, String indexSnapshotId,
                                                      ProjectFingerprint fingerprint) throws IOException {
                throw new IOException("fingerprint publication refused");
            }
        };
        fixture.retentionFailure = new IOException("retention lock timed out");

        ProjectIndexingUseCase useCase = fixture.useCase();
        String projectId = fixture.id();
        IOException failure = assertThrows(IOException.class,
                () -> useCase.execute(projectId, null, false, IndexingResumePolicy.RESUME));

        assertEquals("fingerprint publication refused", failure.getMessage(),
                "D3 (b): the publication failure stays a failure of the indexing");
        assertEquals(1, fixture.compactions.get(), "the failed promotion releases what the run retained");
        assertSame(fixture.retentionFailure, failure.getSuppressed()[0]);
        assertEquals(Optional.of("snapshot-1"), fixture.snapshots.activeSnapshotId(fixture.project.id()),
                "the code snapshot is already promoted when the fingerprint fails");
    }

    @Test
    void aSuccessfulRetentionPassAfterAFailureAddsNothingToIt() throws Exception {
        IndexingFixture fixture = new IndexingFixture(temp);
        IllegalStateException failure = new IllegalStateException("run failed");

        fixture.useCase().compactAfterFailure(UUID.randomUUID(), failure);

        assertEquals(1, fixture.compactions.get());
        assertEquals(0, failure.getSuppressed().length);
    }

    @Test
    void aBusyRetentionAfterASuccessIsADiagnosticNotAFailure() throws Exception {
        IndexingFixture fixture = new IndexingFixture(temp);
        fixture.retentionFailure = new IOException("lock busy at " + temp.toAbsolutePath());

        var outcome = fixture.useCase().execute(fixture.id(), null, false, IndexingResumePolicy.RESUME);

        assertEquals("SUCCEEDED", outcome.status());
        assertEquals(Optional.of(
                "storage retention did not run (its lock was busy or unavailable); it runs after the next indexing"),
                outcome.diagnostic());
    }

    @Test
    void theExecutorDecoratorWrapsEveryExecutorOfTheRun() throws Exception {
        IndexingFixture fixture = new IndexingFixture(temp);
        var decorated = new java.util.concurrent.atomic.AtomicInteger();

        fixture.useCase(executor -> {
            decorated.incrementAndGet();
            return executor;
        }).execute(fixture.id(), null, false, IndexingResumePolicy.RESUME);

        assertEquals(1, decorated.get());
        ProjectIndexingUseCase refusingUseCase = fixture.useCase(executor -> null);
        String projectId = fixture.id();
        assertThrows(NullPointerException.class, () -> refusingUseCase
                .execute(projectId, null, true, IndexingResumePolicy.RESUME));
    }

    @Test
    void aWorkspaceMutationDuringTheRunLeavesTheBaselineBehindAndTheNextIndexingHealsIt() throws Exception {
        IndexingFixture fixture = new IndexingFixture(temp);
        ProjectIndexingUseCase useCase = fixture.useCase();
        fixture.executor.duringRun = () -> write(fixture.root.resolve("src/main/java/App.java"),
                "class App { int concurrent = 1; }");

        var unstable = useCase.execute(fixture.id(), null, false, IndexingResumePolicy.RESUME);
        fixture.executor.duringRun = () -> { };
        var healing = useCase.execute(fixture.id(), null, false, IndexingResumePolicy.RESUME);

        assertEquals("SUCCEEDED", unstable.status());
        assertFalse(unstable.fingerprintPromoted());
        assertTrue(unstable.diagnostic().orElseThrow().contains("workspace changed during indexing"));
        assertEquals(IndexingMode.FULL, healing.preparation().effectiveMode());
        assertEquals(List.of(ProjectInvalidationReason.MISSING_FINGERPRINT_BASELINE),
                healing.preparation().plan().invalidation().reasons());
        assertTrue(healing.fingerprintPromoted());
        assertEquals("snapshot-2", fixture.fingerprints.loadActive(fixture.project.id()).orElseThrow().indexSnapshotId());
    }

    @Test
    void theFailureLineWithholdsACauseThatNamesAPath() {
        IndexingRun run = new IndexingRun(UUID.randomUUID(), UUID.randomUUID(), IndexingRun.Status.INTERRUPTED,
                IndexingRun.Phase.PROVIDER_EXECUTION, Instant.parse("2026-10-21T08:00:00Z"),
                Optional.of(Instant.parse("2026-10-21T08:01:00Z")),
                List.of(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of("cannot read " + temp.toAbsolutePath()));

        String line = ProjectIndexingUseCase.failureMessage(run);

        assertTrue(line.contains("was interrupted and can be resumed"), line);
        assertFalse(line.contains(temp.toAbsolutePath().toString()), line);
    }

    private static void write(Path file, String content) {
        try {
            Files.writeString(file, content);
        } catch (IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }
}
