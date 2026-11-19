package com.minos.orchestration;

import com.minos.orchestration.IndexingRun.Phase;
import com.minos.orchestration.IndexingRun.Status;
import com.minos.orchestration.ProjectIndexState.Availability;

import java.io.IOException;
import java.nio.channels.ClosedByInterruptException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Persists how one run ends: success, interruption offered for resume, or failure, committed or not. The state is
 * written with the interrupt flag cleared and the flag replayed last. One instance per run.
 */
final class IndexingRunOutcomes {
    /** {@link #persist} and {@link #holdAgainstRetention} write again at most this many times under interruption. */
    private static final int PERSISTENCE_ATTEMPTS_UNDER_INTERRUPTION = 3;
    private static final String UNWRITTEN_INTERRUPTION_NOTE =
            "; the interrupted state could not be written, the next indexing reconciles it";

    private final IndexingRunContext context;

    IndexingRunOutcomes(IndexingRunContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    IndexingRun persistSuccess(IndexingMode mode, Instant completedAt) {
        IndexStateStore stateStore = context.ports.stateStore();
        String stagedId = context.staged.orElseThrow();
        String durabilitySuffix = context.durabilityAcknowledgementPending
                ? "; authoritative snapshot confirmed after lost durability acknowledgement"
                : "";
        String checkpointSuffix = context.withheldCheckpoints == 0
                ? ""
                : "; checkpoint withheld for " + context.withheldCheckpoints + "/" + context.totalTargets()
                        + " target(s), not resumable";
        String successMessage = "indexing run completed and snapshot promoted: mode=" + mode
                + ", scopes=" + context.totalTargets() + durabilitySuffix + checkpointSuffix + resumeSuffix();
        IndexingRun succeeded = new IndexingRun(
                context.runId,
                context.projectId,
                Status.SUCCEEDED,
                Phase.COMPLETED,
                context.createdAt,
                Optional.of(completedAt),
                context.executions(),
                context.staged,
                context.previous.activeSnapshotId(),
                Optional.of(stagedId),
                Optional.of(successMessage),
                IndexingRun.CURRENT_FORMAT_VERSION,
                context.trace);
        stateStore.saveRun(succeeded);
        stateStore.saveProjectState(new ProjectIndexState(
                context.projectId,
                Availability.READY,
                Optional.of(stagedId),
                Optional.of(context.runId),
                completedAt,
                Optional.of("active snapshot is current: mode=" + mode
                        + (context.durabilityAcknowledgementPending
                        ? "; durability acknowledgement pending" : ""))));
        AuthoritativeProjectStateReconciler.unmarkQuietly(context.ports.markers(), context.runId);
        return succeeded;
    }

    private String resumeSuffix() {
        return context.trace
                .filter(trace -> context.resumed)
                .map(trace -> "; resumed attempt=" + trace.attempt() + ": " + trace.reusedTargets()
                        + "/" + context.totalTargets() + " targets reused, " + trace.reexecutedTargets() + " re-executed")
                .orElse("");
    }

    /**
     * Terminal outcome of a run that did not complete. A thread interruption (R1-11) leaves an INTERRUPTED
     * run offered for resume, instead of a FAILED one, when the run already owns a checkpoint or a staged
     * snapshot.
     *
     * <p>The state is written with the interrupt flag <em>cleared</em> and the flag is replayed last, on
     * every way out (Q5, R6): with the flag raised, every durable write goes through an interruptible file
     * channel and fails with {@link ClosedByInterruptException}, so the INTERRUPTED run, its marker and the
     * project state would never reach the disk and the run would be reported FAILED with its checkpoints
     * thrown away. The flag is replayed when it was raised on entry as well, whatever the failure was.</p>
     */
    IndexingRun persistTerminalFailure(Exception failure, Instant completedAt) {
        context.clearInterruptFlagForWrite();
        boolean interruption = isInterruption(failure);
        try {
            if (interruption) {
                IndexingRun interrupted = interruptedRun(completedAt, "");
                if (!context.committed && interrupted.offersResume()) {
                    return persistInterruption(interrupted, failure, completedAt);
                }
            }
            return persistFailure(failure, completedAt);
        } finally {
            if (interruption || context.interruptedWhilePersisting) Thread.currentThread().interrupt();
        }
    }

    /**
     * The only place that says "this failure is an interruption": an {@link InterruptedException}, or the
     * {@link ClosedByInterruptException} an interrupted file channel raises, anywhere in the chain of causes.
     * A failure that loses its cause on the way (R6) is therefore a bug at the place that loses it.
     */
    static boolean isInterruption(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof InterruptedException || cause instanceof ClosedByInterruptException) return true;
            if (cause.getCause() == cause) break;
        }
        return false;
    }

    /** The run as it would be left by an interruption now; whether it is offered for resume is its own rule. */
    private IndexingRun interruptedRun(Instant completedAt, String note) {
        return new IndexingRun(
                context.runId,
                context.projectId,
                Status.INTERRUPTED,
                context.phase,
                context.createdAt,
                Optional.of(completedAt),
                context.executions(),
                context.staged,
                context.previous.activeSnapshotId(),
                context.previous.activeSnapshotId(),
                Optional.of("indexing run interrupted by thread interruption; resumable targets="
                        + context.checkpointCount() + "/" + context.totalTargets()
                        + context.staged.map(id -> ", staged snapshot retained").orElse("") + note),
                IndexingRun.CURRENT_FORMAT_VERSION,
                context.trace);
    }

    private IndexingRun persistInterruption(IndexingRun interrupted, Exception failure, Instant completedAt) {
        IndexStateStore stateStore = context.ports.stateStore();
        try {
            holdAgainstRetention();
        } catch (IOException | RuntimeException markerFailure) {
            AuthoritativeProjectStateReconciler.unmarkQuietly(context.ports.markers(), context.runId);
            failure.addSuppressed(markerFailure);
            return persistFailure(failure, completedAt);
        }
        boolean written = persist(() -> stateStore.saveRun(interrupted), failure);
        Availability availability = context.previous.activeSnapshotId().isPresent()
                ? Availability.STALE
                : Availability.FAILED;
        written &= persist(() -> stateStore.saveProjectState(new ProjectIndexState(
                context.projectId,
                availability,
                context.previous.activeSnapshotId(),
                Optional.of(context.runId),
                completedAt,
                Optional.of("indexing run interrupted; resumable run offered"),
                Optional.of(context.runId))), failure);
        // What the caller is told is what the store holds: a state that could not be written is said so, and the
        // next run reconciles it (its checkpoints and its marker are on disk).
        return written ? interrupted : interruptedRun(completedAt, UNWRITTEN_INTERRUPTION_NOTE);
    }

    /**
     * Holds the run directory against retention for the interrupted run. A second interruption closes the marker's
     * file channel like any other write (V-L4-02): the hold is made again, flag cleared, a bounded number of times.
     */
    private void holdAgainstRetention() throws IOException {
        for (int attempt = 1; ; attempt++) {
            context.clearInterruptFlagForWrite();
            try {
                context.ports.markers().mark(context.runId);
                return;
            } catch (IOException failure) {
                if (attempt >= PERSISTENCE_ATTEMPTS_UNDER_INTERRUPTION || !isInterruption(failure)) throw failure;
            }
        }
    }

    private IndexingRun persistFailure(Exception failure, Instant completedAt) {
        String message = failureMessage(failure);
        Optional<String> activeAfter = context.committed ? context.staged : context.previous.activeSnapshotId();
        IndexingRun failed = failedRun(completedAt, activeAfter, message);
        if (context.committed) {
            persistCommittedFailure(failed, activeAfter, message, completedAt, failure);
        } else {
            persistUncommittedFailure(failed, message, completedAt, failure);
        }
        context.clearInterruptFlagForWrite();
        AuthoritativeProjectStateReconciler.unmarkQuietly(context.ports.markers(), context.runId);
        return failed;
    }

    private IndexingRun failedRun(Instant completedAt, Optional<String> activeAfter, String message) {
        String committedPrefix = context.durabilityAcknowledgementPending
                ? "snapshot promotion is authoritative after lost durability acknowledgement; metadata finalization failed: "
                : "snapshot promotion committed; metadata finalization failed: ";
        return new IndexingRun(
                context.runId,
                context.projectId,
                Status.FAILED,
                context.phase,
                context.createdAt,
                Optional.of(completedAt),
                context.executions(),
                context.staged,
                context.previous.activeSnapshotId(),
                activeAfter,
                Optional.of(context.committed ? committedPrefix + message : message),
                IndexingRun.CURRENT_FORMAT_VERSION,
                context.trace);
    }

    private void persistCommittedFailure(IndexingRun failed, Optional<String> activeAfter, String message,
                                         Instant completedAt, Exception original) {
        IndexStateStore stateStore = context.ports.stateStore();
        persist(() -> stateStore.saveProjectState(new ProjectIndexState(
                context.projectId,
                Availability.READY,
                activeAfter,
                Optional.of(context.runId),
                completedAt,
                Optional.of("active snapshot committed; run metadata recovery required: " + message))), original);
        persist(() -> stateStore.saveRun(failed), original);
    }

    private void persistUncommittedFailure(IndexingRun failed, String message, Instant completedAt,
                                           Exception original) {
        IndexStateStore stateStore = context.ports.stateStore();
        persist(() -> stateStore.saveRun(failed), original);
        Availability availability = context.previous.activeSnapshotId().isPresent()
                ? Availability.STALE
                : Availability.FAILED;
        persist(() -> stateStore.saveProjectState(new ProjectIndexState(
                context.projectId,
                availability,
                context.previous.activeSnapshotId(),
                Optional.of(context.runId),
                completedAt,
                Optional.of(message))), original);
    }

    /**
     * One write of the terminal state. A write that fails because the thread was interrupted again (the file
     * channel closes on the raised flag, V-L4-02) is made again with the flag cleared, a bounded number of times;
     * any other failure, and the last one, is kept as suppressed on the original failure and reported as
     * {@code false}. A flag cleared on the way is replayed when the terminal outcome is over.
     */
    private boolean persist(Runnable action, Exception original) {
        for (int attempt = 1; ; attempt++) {
            context.clearInterruptFlagForWrite();
            try {
                action.run();
                return true;
            } catch (IndexStateStoreException failure) {
                // AUD-QUA-03: only a storage failure of the index state is retried or kept as suppressed; a
                // programming error goes up as it is.
                if (attempt >= PERSISTENCE_ATTEMPTS_UNDER_INTERRUPTION || !isInterruption(failure)) {
                    original.addSuppressed(failure);
                    return false;
                }
            }
        }
    }

    private static String failureMessage(Exception exception) {
        String message = exception.getMessage();
        return exception.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }
}
