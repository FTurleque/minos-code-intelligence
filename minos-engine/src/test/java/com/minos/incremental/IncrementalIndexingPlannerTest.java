package com.minos.incremental;

import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexerCapability;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexerNegotiationResult;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;
import com.minos.orchestration.IndexerQualification;
import com.minos.orchestration.IndexingMode;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IncrementalIndexingPlannerTest {

    private static final String A = "a".repeat(64);
    private static final String B = "b".repeat(64);
    private static final String C = "c".repeat(64);
    private final IncrementalIndexingPlanner planner = new IncrementalIndexingPlanner();

    @Test
    void returnsNoneWhenWorkspaceDidNotChange() {
        UUID projectId = UUID.randomUUID();
        ProjectChangeSet unchanged = new ProjectChangeSet(
                A, A, C, C, false, false,
                List.of(), List.of(), List.of(), List.of("src/App.java")
        );
        ProjectInvalidationAssessment assessment = new ProjectInvalidationAssessment(
                projectId,
                Optional.of("snapshot-1"),
                Optional.of("snapshot-1"),
                ProjectInvalidationScope.NONE,
                List.of(),
                Optional.of(unchanged),
                List.of(),
                List.of(),
                List.of()
        );

        IncrementalIndexingPlan plan = planner.plan(
                assessment,
                negotiation(selection("java-indexer", Language.JAVA, true))
        );

        assertEquals(IndexingMode.NONE, plan.mode());
        assertEquals(List.of(IncrementalIndexingPlanReason.NO_CHANGES), plan.reasons());
        assertEquals(List.of(), plan.changedFiles());
    }

    @Test
    void keepsFullInvalidationFullEvenWhenProviderSupportsIncremental() {
        UUID projectId = UUID.randomUUID();
        ProjectInvalidationAssessment assessment = new ProjectInvalidationAssessment(
                projectId,
                Optional.of("snapshot-1"),
                Optional.empty(),
                ProjectInvalidationScope.FULL_REQUIRED,
                List.of(ProjectInvalidationReason.MISSING_FINGERPRINT_BASELINE),
                Optional.empty(),
                List.of(),
                List.of(),
                List.of()
        );

        IncrementalIndexingPlan plan = planner.plan(
                assessment,
                negotiation(selection("java-indexer", Language.JAVA, true))
        );

        assertEquals(IndexingMode.FULL, plan.mode());
        assertEquals(List.of(IncrementalIndexingPlanReason.INVALIDATION_REQUIRES_FULL), plan.reasons());
    }

    @Test
    void selectsIncrementalOnlyWhenEverySelectedIndexerIsQualified() {
        UUID projectId = UUID.randomUUID();
        ProjectInvalidationAssessment assessment = partial(projectId);

        IncrementalIndexingPlan plan = planner.plan(
                assessment,
                negotiation(
                        selection("java-indexer", Language.JAVA, true),
                        selection("typescript-indexer", Language.TYPESCRIPT, true)
                )
        );

        assertEquals(IndexingMode.INCREMENTAL, plan.mode());
        assertEquals(List.of("java-indexer", "typescript-indexer"), plan.incrementalCapableIndexerIds());
        assertEquals(List.of(), plan.missingIncrementalCapabilityIndexerIds());
        assertEquals(List.of("src/App.java"), plan.changedFiles());
    }

    @Test
    void fallsBackWholeProjectToFullWhenOneSelectedIndexerIsNotQualified() {
        UUID projectId = UUID.randomUUID();
        ProjectInvalidationAssessment assessment = partial(projectId);

        IncrementalIndexingPlan plan = planner.plan(
                assessment,
                negotiation(
                        selection("java-indexer", Language.JAVA, true),
                        selection("typescript-indexer", Language.TYPESCRIPT, false)
                )
        );

        assertEquals(IndexingMode.FULL, plan.mode());
        assertEquals(List.of("java-indexer"), plan.incrementalCapableIndexerIds());
        assertEquals(List.of("typescript-indexer"), plan.missingIncrementalCapabilityIndexerIds());
        assertEquals(
                List.of(IncrementalIndexingPlanReason.INDEXER_INCREMENTAL_CAPABILITY_MISSING),
                plan.reasons()
        );
    }

    private static ProjectInvalidationAssessment partial(UUID projectId) {
        ProjectChangeSet changeSet = new ProjectChangeSet(
                A, B, C, C, true, false,
                List.of(), List.of("src/App.java"), List.of(), List.of()
        );
        return new ProjectInvalidationAssessment(
                projectId,
                Optional.of("snapshot-1"),
                Optional.of("snapshot-1"),
                ProjectInvalidationScope.PARTIAL_CANDIDATE,
                List.of(ProjectInvalidationReason.SOURCE_OR_TEST_CHANGED),
                Optional.of(changeSet),
                List.of("src/App.java"),
                List.of(),
                List.of()
        );
    }

    private static IndexerSelection selection(String id, Language language, boolean incremental) {
        EnumSet<IndexerCapability> capabilities = EnumSet.of(
                IndexerCapability.SYMBOLS,
                IndexerCapability.REFERENCES
        );
        if (incremental) {
            capabilities.add(IndexerCapability.INCREMENTAL_INDEXING);
        }
        return new IndexerSelection(
                language,
                new IndexerDescriptor(
                        id,
                        "1.0",
                        id,
                        Set.of(language),
                        Set.of(),
                        capabilities,
                        IndexerQualification.QUALIFIED,
                        100,
                        List.of()
                )
        );
    }

    private static IndexerNegotiationResult negotiation(IndexerSelection... selections) {
        return new IndexerNegotiationResult(List.of(selections), Set.of(), List.of());
    }
}
