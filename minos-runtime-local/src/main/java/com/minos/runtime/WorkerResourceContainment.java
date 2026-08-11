package com.minos.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Per-dimension disposition of the aggregate resource containment a worker sandbox backend can
 * actually enforce on the current host.
 *
 * <p>The record deliberately separates three very different things that an audit must never see
 * conflated:</p>
 *
 * <ul>
 *   <li>{@link Disposition#OS_ENFORCED} — the kernel refuses the excess itself. The provider cannot
 *       exceed the bound even if MINOS is killed, and no {@code fork} can escape it.</li>
 *   <li>{@link Disposition#SUPERVISED_HARD_KILL} — MINOS observes the dimension <em>during</em>
 *       execution at a bounded period and destroys the whole OS job boundary on breach.</li>
 *   <li>{@link Disposition#MEASURED_ONLY} — the value is only known after the fact. This is a
 *       diagnostic, never a containment guarantee.</li>
 * </ul>
 *
 * <p>A per-process limit that a provider can multiply by forking is not an aggregate guarantee and
 * must never be declared {@link Disposition#OS_ENFORCED} on an aggregate dimension.</p>
 */
public record WorkerResourceContainment(
        String boundaryId,
        Disposition aggregateProcessCount,
        Disposition aggregateMemory,
        Disposition aggregateCpu,
        Disposition wallClock,
        Disposition filesystemWriteBytes,
        Disposition filesystemWriteEntries,
        Disposition descendantTermination,
        Disposition scratchReclamation,
        List<String> evidence
) {

    public WorkerResourceContainment {
        if (boundaryId == null || boundaryId.isBlank()) {
            throw new IllegalArgumentException("containment boundaryId must not be blank");
        }
        Objects.requireNonNull(aggregateProcessCount, "aggregateProcessCount");
        Objects.requireNonNull(aggregateMemory, "aggregateMemory");
        Objects.requireNonNull(aggregateCpu, "aggregateCpu");
        Objects.requireNonNull(wallClock, "wallClock");
        Objects.requireNonNull(filesystemWriteBytes, "filesystemWriteBytes");
        Objects.requireNonNull(filesystemWriteEntries, "filesystemWriteEntries");
        Objects.requireNonNull(descendantTermination, "descendantTermination");
        Objects.requireNonNull(scratchReclamation, "scratchReclamation");
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    /**
     * Dimensions that a MINOS-side supervisor can never substitute for: without a real OS job
     * boundary a forked descendant simply multiplies the limit or outlives MINOS.
     */
    public boolean aggregateJobBoundaryEnforced() {
        return aggregateProcessCount == Disposition.OS_ENFORCED
                && aggregateMemory == Disposition.OS_ENFORCED
                && aggregateCpu == Disposition.OS_ENFORCED
                && descendantTermination == Disposition.OS_ENFORCED;
    }

    /** Returns true only when every P1 containment dimension is actually enforced. */
    public boolean qualifiedForUntrustedCode() {
        return unmetRequirements().isEmpty();
    }

    /** Machine-readable reasons why untrusted code cannot be contained here. */
    public List<String> unmetRequirements() {
        List<String> unmet = new ArrayList<>();
        requireOsEnforced(unmet, "AGGREGATE_PROCESS_COUNT", aggregateProcessCount);
        requireOsEnforced(unmet, "AGGREGATE_MEMORY", aggregateMemory);
        requireOsEnforced(unmet, "AGGREGATE_CPU", aggregateCpu);
        requireOsEnforced(unmet, "DESCENDANT_TERMINATION", descendantTermination);
        requireEnforced(unmet, "WALL_CLOCK", wallClock);
        requireEnforced(unmet, "FILESYSTEM_WRITE_BYTES", filesystemWriteBytes);
        requireEnforced(unmet, "FILESYSTEM_WRITE_ENTRIES", filesystemWriteEntries);
        requireEnforced(unmet, "SCRATCH_RECLAMATION", scratchReclamation);
        return List.copyOf(unmet);
    }

    private static void requireOsEnforced(List<String> unmet, String dimension, Disposition value) {
        if (value != Disposition.OS_ENFORCED) {
            unmet.add(dimension + "_REQUIRES_OS_ENFORCED_JOB_BOUNDARY_BUT_IS_" + value.name());
        }
    }

    private static void requireEnforced(List<String> unmet, String dimension, Disposition value) {
        if (!value.enforcedDuringExecution()) {
            unmet.add(dimension + "_NOT_ENFORCED_DURING_EXECUTION_BUT_IS_" + value.name());
        }
    }

    /** Containment disposition of a backend that owns no aggregate job boundary at all. */
    public static WorkerResourceContainment none(String boundaryId) {
        return new WorkerResourceContainment(
                boundaryId,
                Disposition.UNAVAILABLE,
                Disposition.UNAVAILABLE,
                Disposition.UNAVAILABLE,
                Disposition.SUPERVISED_HARD_KILL,
                Disposition.MEASURED_ONLY,
                Disposition.MEASURED_ONLY,
                Disposition.MEASURED_ONLY,
                Disposition.MEASURED_ONLY,
                List.of(
                        "WORKER_AGGREGATE_RESOURCE_JOB_BOUNDARY_UNAVAILABLE",
                        "WORKER_PER_PROCESS_LIMITS_ARE_NOT_AGGREGATE_GUARANTEES",
                        "WORKER_UNTRUSTED_CODE_CONTAINMENT_FAIL_CLOSED"));
    }

    public enum Disposition {
        /** The operating system itself refuses the excess; MINOS cannot be bypassed or outlived. */
        OS_ENFORCED,
        /** MINOS samples during execution at a bounded period and destroys the OS job on breach. */
        SUPERVISED_HARD_KILL,
        /** Observed after the fact only: diagnostic value, never a containment guarantee. */
        MEASURED_ONLY,
        /** The primitive does not exist on this host or configuration. */
        UNAVAILABLE;

        public boolean enforcedDuringExecution() {
            return this == OS_ENFORCED || this == SUPERVISED_HARD_KILL;
        }
    }
}
