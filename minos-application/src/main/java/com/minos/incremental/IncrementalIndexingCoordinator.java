package com.minos.incremental;

import com.minos.discovery.ProjectDiscovery;
import com.minos.discovery.ProjectDiscoveryService;
import com.minos.orchestration.IndexerNegotiationResult;
import com.minos.orchestration.IndexerRegistry;
import com.minos.orchestration.IndexingLifecycleService;
import com.minos.orchestration.IndexingRequirements;
import com.minos.orchestration.IndexingRun;
import com.minos.orchestration.ProjectIndexState;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestration M7 complète : découverte, empreinte, invalidation, négociation,
 * planification, exécution et avancement sûr de la baseline fingerprint.
 */
public final class IncrementalIndexingCoordinator {

    private final ProjectDiscoveryService discoveryService;
    private final ProjectFingerprintService fingerprintService;
    private final ProjectFingerprintSnapshotStore fingerprintStore;
    private final ProjectInvalidationService invalidationService;
    private final IncrementalIndexingPlanner planner;
    private final IndexerRegistry indexerRegistry;
    private final IndexingLifecycleService lifecycleService;

    public IncrementalIndexingCoordinator(
            ProjectFingerprintSnapshotStore fingerprintStore,
            IndexerRegistry indexerRegistry,
            IndexingLifecycleService lifecycleService
    ) {
        this(
                new ProjectDiscoveryService(),
                new ProjectFingerprintService(),
                fingerprintStore,
                new ProjectInvalidationService(),
                new IncrementalIndexingPlanner(),
                indexerRegistry,
                lifecycleService
        );
    }

    IncrementalIndexingCoordinator(
            ProjectDiscoveryService discoveryService,
            ProjectFingerprintService fingerprintService,
            ProjectFingerprintSnapshotStore fingerprintStore,
            ProjectInvalidationService invalidationService,
            IncrementalIndexingPlanner planner,
            IndexerRegistry indexerRegistry,
            IndexingLifecycleService lifecycleService
    ) {
        this.discoveryService = Objects.requireNonNull(discoveryService, "discoveryService");
        this.fingerprintService = Objects.requireNonNull(fingerprintService, "fingerprintService");
        this.fingerprintStore = Objects.requireNonNull(fingerprintStore, "fingerprintStore");
        this.invalidationService = Objects.requireNonNull(invalidationService, "invalidationService");
        this.planner = Objects.requireNonNull(planner, "planner");
        this.indexerRegistry = Objects.requireNonNull(indexerRegistry, "indexerRegistry");
        this.lifecycleService = Objects.requireNonNull(lifecycleService, "lifecycleService");
    }

    public IncrementalIndexingResult refresh(
            UUID projectId,
            Path projectRoot,
            IndexingRequirements requirements
    ) throws IOException {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(projectRoot, "projectRoot");
        Objects.requireNonNull(requirements, "requirements");

        ProjectDiscovery discovery = discoveryService.discover(projectRoot);
        ProjectFingerprint before = fingerprintService.capture(projectRoot);
        ProjectIndexState indexState = lifecycleService.projectState(projectId);

        BaselineLoad baselineLoad = loadBaselineConservatively(projectId, indexState);
        ProjectInvalidationAssessment invalidation = baselineLoad.unreadable()
                ? unreadableBaselineAssessment(indexState)
                : invalidationService.assess(indexState, baselineLoad.snapshot(), before, discovery);

        IndexerNegotiationResult negotiation = indexerRegistry.negotiate(discovery, requirements);
        IncrementalIndexingPlan plan = planner.plan(invalidation, negotiation);
        Optional<IndexingRun> run = lifecycleService.executePlanned(projectId, projectRoot, negotiation, plan);

        if (run.isEmpty()) {
            return new IncrementalIndexingResult(
                    negotiation,
                    plan,
                    Optional.empty(),
                    true,
                    false,
                    baselineLoad.diagnostic()
            );
        }

        IndexingRun completedRun = run.orElseThrow();
        if (completedRun.status() != IndexingRun.Status.SUCCEEDED) {
            return new IncrementalIndexingResult(
                    negotiation,
                    plan,
                    run,
                    false,
                    false,
                    mergeDiagnostic(baselineLoad.diagnostic(), "indexing run failed; fingerprint baseline unchanged")
            );
        }

        ProjectFingerprint after = fingerprintService.capture(projectRoot);
        boolean stable = before.equals(after);
        if (!stable) {
            return new IncrementalIndexingResult(
                    negotiation,
                    plan,
                    run,
                    false,
                    false,
                    mergeDiagnostic(
                            baselineLoad.diagnostic(),
                            "workspace changed during indexing; promoted code snapshot is not assigned a fingerprint baseline"
                    )
            );
        }

        String activeSnapshotId = completedRun.activeSnapshotAfter().orElseThrow();
        try {
            fingerprintStore.publish(projectId, activeSnapshotId, after);
            fingerprintStore.promote(projectId, activeSnapshotId);
            return new IncrementalIndexingResult(
                    negotiation,
                    plan,
                    run,
                    true,
                    true,
                    baselineLoad.diagnostic()
            );
        } catch (IOException exception) {
            return new IncrementalIndexingResult(
                    negotiation,
                    plan,
                    run,
                    true,
                    false,
                    mergeDiagnostic(
                            baselineLoad.diagnostic(),
                            "fingerprint baseline publication failed: " + exception.getMessage()
                    )
            );
        }
    }

    private BaselineLoad loadBaselineConservatively(UUID projectId, ProjectIndexState indexState) {
        try {
            return new BaselineLoad(fingerprintStore.loadActive(projectId), false, Optional.empty());
        } catch (IOException exception) {
            return new BaselineLoad(
                    Optional.empty(),
                    true,
                    Optional.of("active fingerprint baseline unreadable: " + safeMessage(exception))
            );
        }
    }

    private static ProjectInvalidationAssessment unreadableBaselineAssessment(ProjectIndexState indexState) {
        return new ProjectInvalidationAssessment(
                indexState.projectId(),
                indexState.activeSnapshotId(),
                Optional.empty(),
                ProjectInvalidationScope.FULL_REQUIRED,
                List.of(ProjectInvalidationReason.FINGERPRINT_BASELINE_UNREADABLE),
                Optional.empty(),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private static Optional<String> mergeDiagnostic(Optional<String> first, String second) {
        if (first.isEmpty()) {
            return Optional.of(second);
        }
        return Optional.of(first.orElseThrow() + "; " + second);
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private record BaselineLoad(
            Optional<ProjectFingerprintSnapshot> snapshot,
            boolean unreadable,
            Optional<String> diagnostic
    ) {
        private BaselineLoad {
            snapshot = Objects.requireNonNull(snapshot, "snapshot");
            diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
            if (unreadable && snapshot.isPresent()) {
                throw new IllegalArgumentException("unreadable baseline cannot expose a snapshot");
            }
        }
    }
}
