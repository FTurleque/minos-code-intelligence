package com.minos.orchestration;

import com.minos.discovery.ModuleAssignmentRule;
import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.incremental.ProjectFingerprintService;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;
import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AUD-QUA-07 (caractérisation) : ordre et messages des refus d'un run d'indexation, avant toute écriture. La
 * politique de reprise est vérifiée d'abord, puis la racine, puis les ports dans l'ordre de leur record ; un refus
 * ne crée aucun run.
 */
class IndexingRunExecutorValidationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-24T08:00:00Z"), ZoneOffset.UTC);
    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000707");

    @Test
    void theResumePolicyIsCheckedBeforeTheRoot(@TempDir Path temp) {
        InMemoryIndexStateStore store = new InMemoryIndexStateStore();
        Path missing = temp.resolve("missing");
        ProjectFingerprintService fingerprints = new ProjectFingerprintService();

        NullPointerException refusal = assertThrows(NullPointerException.class,
                () -> run(store, missing, null, null, fingerprints));

        assertEquals("resumePolicy", refusal.getMessage());
        assertTrue(store.listRuns(PROJECT_ID).isEmpty());
    }

    @Test
    void anInvalidRootIsRefusedBeforeAMissingModuleAssignment(@TempDir Path temp) {
        InMemoryIndexStateStore store = new InMemoryIndexStateStore();
        Path missing = temp.resolve("missing");
        ProjectFingerprintService fingerprints = new ProjectFingerprintService();

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> run(store, missing, IndexingResumePolicy.RESUME, null, fingerprints));

        // AUD-SEC-04: the refusal names the root by its file name, never by its absolute path.
        assertEquals("projectRoot must be an existing directory: missing", refusal.getMessage());
        assertFalse(refusal.getMessage().contains(temp.toString()), refusal.getMessage());
        assertTrue(store.listRuns(PROJECT_ID).isEmpty());
    }

    @Test
    void aMissingModuleAssignmentIsRefusedByNameOnAValidRoot(@TempDir Path temp) {
        InMemoryIndexStateStore store = new InMemoryIndexStateStore();
        ProjectFingerprintService fingerprints = new ProjectFingerprintService();

        NullPointerException refusal = assertThrows(NullPointerException.class,
                () -> run(store, temp, IndexingResumePolicy.RESUME, null, fingerprints));

        assertEquals("moduleAssignment", refusal.getMessage());
        assertTrue(store.listRuns(PROJECT_ID).isEmpty());
    }

    @Test
    void portsAreCheckedInTheirRecordOrder(@TempDir Path temp) {
        InMemoryIndexStateStore store = new InMemoryIndexStateStore();

        NullPointerException refusal = assertThrows(NullPointerException.class, () -> run(store,
                temp, IndexingResumePolicy.RESUME, null, null));

        assertEquals("fingerprints", refusal.getMessage());
        assertTrue(store.listRuns(PROJECT_ID).isEmpty());
    }

    @Test
    void resumeOnlyWithoutAnInterruptedRunIsRefusedWithItsExactMessage(@TempDir Path temp) {
        InMemoryIndexStateStore store = new InMemoryIndexStateStore();
        IndexingLifecycleService service = new IndexingLifecycleService(
                List.of(executor()), request -> "unused", (projectId, runId, staged) -> { }, store, CLOCK);

        var negotiation = negotiation();

        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> service.execute(PROJECT_ID, temp, negotiation, IndexingResumePolicy.RESUME_ONLY));

        assertEquals("resume-only indexing refused: no interrupted run to resume", refusal.getMessage());
        assertTrue(store.listRuns(PROJECT_ID).isEmpty());
    }

    @Test
    void aMissingPolicyIsRefusedByTheLifecycleBeforeAnyRun(@TempDir Path temp) {
        InMemoryIndexStateStore store = new InMemoryIndexStateStore();
        IndexingLifecycleService service = new IndexingLifecycleService(
                List.of(executor()), request -> "unused", (projectId, runId, staged) -> { }, store, CLOCK);

        var negotiation = negotiation();

        NullPointerException refusal = assertThrows(NullPointerException.class,
                () -> service.execute(PROJECT_ID, temp, negotiation, null));

        assertEquals("policy", refusal.getMessage());
        assertTrue(store.listRuns(PROJECT_ID).isEmpty());
    }

    private static IndexingRun run(InMemoryIndexStateStore store, Path root, IndexingResumePolicy policy,
                                   ModuleAssignmentRule moduleAssignment, ProjectFingerprintService fingerprints) {
        IndexerExecutor executor = executor();
        return IndexingRunExecutor.execute(
                new IndexingRunExecutor.RunRequest(PROJECT_ID, root,
                        List.of(new IndexingExecutionTarget(selection(), Path.of(""))), IndexingMode.FULL, List.of(),
                        policy, moduleAssignment, Optional.empty()),
                new IndexingRunExecutor.Collaborators(Map.of(executor.indexerId(), executor), request -> "unused",
                        (projectId, runId, staged) -> { }, store, ResumableRunMarkers.none(),
                        ResumableArtifactPolicy.DEFAULT, CLOCK, fingerprints));
    }

    private static IndexerExecutor executor() {
        return new IndexerExecutor() {
            @Override public String indexerId() { return "java-indexer"; }
            @Override public IndexingArtifact execute(IndexingRuntimePorts.IndexingExecutionRequest request) {
                throw new AssertionError("no provider runs on a refused request");
            }
        };
    }

    private static IndexerNegotiationResult negotiation() {
        return new IndexerNegotiationResult(List.of(selection()), Set.of(), List.of());
    }

    private static IndexerSelection selection() {
        IndexerDescriptor descriptor = new IndexerDescriptor(
                "java-indexer", "1.0", "java-indexer", Set.of(Language.JAVA), Set.of(),
                EnumSet.of(IndexerCapability.SYMBOLS, IndexerCapability.REFERENCES),
                IndexerQualification.QUALIFIED, 100, List.of());
        return new IndexerSelection(Language.JAVA, descriptor);
    }
}
