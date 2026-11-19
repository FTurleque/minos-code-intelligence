package com.minos.orchestration;

import com.minos.io.CommitUncertainException;
import com.minos.orchestration.IndexingRun.Phase;
import com.minos.orchestration.IndexingRuntimePorts.ActiveSnapshotObservation;
import com.minos.orchestration.IndexingRuntimePorts.IndexSnapshotStageRequest;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotPromoter;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Stages the snapshot of one run from its confined artifacts, then promotes it, recovering a promotion whose
 * commit is uncertain when the authoritative store shows the staged snapshot active. One instance per run.
 */
final class IndexingSnapshotPublication {
    private final IndexingRunContext context;
    private final IndexingTargetExecution targets;

    IndexingSnapshotPublication(IndexingRunContext context, IndexingTargetExecution targets) {
        this.context = Objects.requireNonNull(context, "context");
        this.targets = Objects.requireNonNull(targets, "targets");
    }

    void stage(IndexingMode mode) throws Exception {
        targets.requireArtifactsStillInsideRunDirectory();
        context.phase = Phase.STAGING;
        context.ports.stateStore().saveRun(context.running("staging project snapshot: mode=" + mode));
        String stagedId = context.ports.stager().stage(new IndexSnapshotStageRequest(
                context.runId, context.projectId, context.artifacts(), context.ports.moduleAssignment()));
        if (stagedId == null || stagedId.isBlank()) {
            // Invalid internal state (the staging port returned a blank identifier), not an argument:
            // IllegalStateException, whose class name appears in the message of the persisted run.
            throw new IllegalStateException("stagedSnapshotId must not be blank");
        }
        context.staged = Optional.of(stagedId);
        context.phase = Phase.PROMOTION;
        context.ports.stateStore().saveRun(context.running("promoting staged snapshot: mode=" + mode));
    }

    void promote() throws Exception {
        String stagedId = context.staged.orElseThrow();
        try {
            context.ports.promoter().promote(context.projectId, context.runId, stagedId);
            context.committed = true;
        } catch (CommitUncertainException uncertain) {
            recoverUncertainPromotion(stagedId, uncertain);
        }
    }

    private void recoverUncertainPromotion(String stagedId, CommitUncertainException uncertain)
            throws CommitUncertainException {
        if (!authoritativeTargetIsActive(context.ports.promoter(), context.projectId, stagedId, uncertain)) throw uncertain;
        context.committed = true;
        context.durabilityAcknowledgementPending = true;
    }

    private static boolean authoritativeTargetIsActive(
            SnapshotPromoter promoter,
            UUID projectId,
            String stagedId,
            CommitUncertainException uncertain
    ) {
        try {
            ActiveSnapshotObservation observation = promoter.observeActiveSnapshot(projectId);
            return observation.status() == ActiveSnapshotObservation.Status.ACTIVE
                    && observation.snapshotId().filter(stagedId::equals).isPresent();
        } catch (Exception observationFailure) {
            uncertain.addSuppressed(observationFailure);
            return false;
        }
    }
}
