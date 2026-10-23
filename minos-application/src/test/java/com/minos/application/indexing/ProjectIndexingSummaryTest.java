package com.minos.application.indexing;

import com.minos.application.ProviderRuntimeSummary;
import com.minos.orchestration.IndexingRun;
import com.minos.runtime.ProviderRuntimeStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AUD-ARC-01: the indexing use case returns application summaries in JDK types, so a surface projects them without
 * naming a type of the engine. The values are the ones the command rendered from the engine types.
 */
class ProjectIndexingSummaryTest {

    @TempDir Path temp;

    @Test
    void aPlanSummaryCarriesTheProjectTheDiscoveryAndTheProviders() throws Exception {
        IndexingFixture fixture = new IndexingFixture(temp);

        IndexPlanSummary plan = fixture.useCase().planSummary(fixture.id(), null, true);

        assertEquals(fixture.id(), plan.projectId());
        assertEquals("demo", plan.projectName());
        assertEquals(fixture.root.toString(), plan.rootPath());
        assertEquals(List.of("JAVA"), plan.languages());
        assertEquals(List.of("MAVEN"), plan.buildSystems());
        assertEquals(List.of(IndexingFixture.INDEXER), plan.providerIds());
        assertEquals(List.of(new ProviderRuntimeSummary(IndexingFixture.INDEXER, "1.0", "READY", Optional.empty(),
                List.of(), true)), plan.providerRuntimes());
        assertEquals("FULL", plan.mode());
        assertTrue(plan.reasons().contains("FORCED_FULL"), plan.reasons().toString());
        assertEquals(List.of(), plan.changedFiles());
        assertTrue(plan.forcedFull());
        assertTrue(fixture.stateStore.findProjectState(fixture.project.id()).isEmpty(), "a plan writes nothing");
    }

    @Test
    void anExecutionSummaryCarriesTheRunItsStatusAndTheIncrementalPlan() throws Exception {
        IndexingFixture fixture = new IndexingFixture(temp);
        ProjectIndexingUseCase useCase = fixture.useCase();

        IndexExecutionSummary first = useCase.executeSummary(fixture.id(), null, false, IndexingResumeChoice.RESUME);
        IndexExecutionSummary unchanged = useCase.executeSummary(fixture.id(), null, false, IndexingResumeChoice.RESUME);
        Files.writeString(fixture.root.resolve("src/main/java/App.java"), "class App { int changed = 1; }");
        IndexExecutionSummary incremental =
                useCase.executeSummary(fixture.id(), null, false, IndexingResumeChoice.RESUME);

        assertEquals("FULL", first.plan().mode());
        assertEquals("SUCCEEDED", first.status());
        IndexingRun run = fixture.stateStore.listRuns(fixture.project.id()).getFirst();
        assertEquals(Optional.of(run.id().toString()), first.runId());
        assertEquals(Optional.of("snapshot-1"), first.activeSnapshotId());
        assertTrue(first.fingerprintPromoted());
        assertEquals(Optional.empty(), first.diagnostic());
        assertEquals(Optional.empty(), first.resumed(), "no resume was considered");

        assertEquals("NONE", unchanged.plan().mode());
        assertEquals("NO_CHANGES", unchanged.status());
        assertEquals(Optional.empty(), unchanged.runId());

        assertEquals("INCREMENTAL", incremental.plan().mode());
        assertEquals(List.of("src/main/java/App.java"), incremental.plan().changedFiles());
        assertFalse(incremental.plan().forcedFull());
        assertEquals(Optional.of("snapshot-2"), incremental.activeSnapshotId());
    }

    @Test
    void theNoResumeChoiceIsACompleteRunAndResumeOnlyIsRefusedOnAnUnchangedProject() throws Exception {
        IndexingFixture fixture = new IndexingFixture(temp);
        ProjectIndexingUseCase useCase = fixture.useCase();
        useCase.executeSummary(fixture.id(), null, false, IndexingResumeChoice.RESUME);

        IndexExecutionSummary noResume =
                useCase.executeSummary(fixture.id(), null, false, IndexingResumeChoice.NO_RESUME);
        String projectId = fixture.id();
        IllegalStateException resumeOnly = assertThrows(IllegalStateException.class,
                () -> useCase.executeSummary(projectId, null, false, IndexingResumeChoice.RESUME_ONLY));

        assertEquals("SUCCEEDED", noResume.status());
        assertEquals("resume-only indexing refused: no changes to index", resumeOnly.getMessage());
        assertThrows(NullPointerException.class, () -> useCase.executeSummary(projectId, null, false, null));
    }

    @Test
    void aRuntimeThatIsNotReadyIsSummarizedInThePlan() throws Exception {
        IndexingFixture fixture = new IndexingFixture(temp);
        fixture.runtimeState = ProviderRuntimeStatus.State.NOT_INSTALLED;

        IndexPlanSummary plan = fixture.useCase().planSummary(fixture.id(), null, false);

        ProviderRuntimeSummary runtime = plan.providerRuntimes().getFirst();
        assertEquals("NOT_INSTALLED", runtime.state());
        assertEquals(List.of("not installed"), runtime.diagnostics());
    }

    @Test
    void theResumeTraceIsSummarizedWithItsPublicRefusal() {
        assertEquals(new ResumeTraceSummary(2, 3, 1, Optional.empty()),
                ResumeTraceSummary.of(new IndexingRun.ResumeTrace(2, 3, 1, Optional.empty())));
        assertEquals(new ResumeTraceSummary(1, 0, 4, Optional.of("checkpoint stale")),
                ResumeTraceSummary.of(new IndexingRun.ResumeTrace(1, 0, 4, Optional.of("checkpoint stale"))));
        assertThrows(IllegalArgumentException.class, () -> new ResumeTraceSummary(0, 0, 0, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new ResumeTraceSummary(1, -1, 0, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new ResumeTraceSummary(1, 0, -1, Optional.empty()));
        assertThrows(NullPointerException.class, () -> new ResumeTraceSummary(1, 0, 0, null));
    }

    @Test
    void theSummariesRefuseMissingParts() throws Exception {
        IndexingFixture fixture = new IndexingFixture(temp);
        IndexPlanSummary plan = fixture.useCase().planSummary(fixture.id(), null, true);

        assertThrows(IllegalArgumentException.class, () -> new IndexPlanSummary(" ", "demo", "/p", List.of(),
                List.of(), List.of(), List.of(), "FULL", List.of(), List.of(), false));
        assertThrows(IllegalArgumentException.class, () -> new IndexPlanSummary("id", "demo", "/p", List.of(),
                List.of(), List.of(), List.of(), "", List.of(), List.of(), false));
        assertThrows(NullPointerException.class, () -> new IndexPlanSummary("id", "demo", "/p", null,
                List.of(), List.of(), List.of(), "FULL", List.of(), List.of(), false));
        assertThrows(NullPointerException.class, () -> new IndexExecutionSummary(null, Optional.empty(), "SUCCEEDED",
                Optional.empty(), true, Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new IndexExecutionSummary(plan, Optional.empty(), " ",
                Optional.empty(), true, Optional.empty(), Optional.empty()));
        assertThrows(NullPointerException.class, () -> new IndexExecutionSummary(plan, null, "SUCCEEDED",
                Optional.empty(), true, Optional.empty(), Optional.empty()));
        assertThrows(NullPointerException.class, () -> new IndexExecutionSummary(plan, Optional.empty(), "SUCCEEDED",
                Optional.empty(), true, Optional.empty(), null));
    }
}
