package com.minos.runtime;

import java.util.List;

/** Containment dispositions used by worker fixtures that stand in for a qualified OS sandbox. */
final class WorkerContainmentFixtures {

    /** A fixture boundary that enforces every P1 containment dimension. */
    static final WorkerResourceContainment FULLY_CONTAINED = new WorkerResourceContainment(
            "test-fixture-os-job",
            WorkerResourceContainment.Disposition.OS_ENFORCED,
            WorkerResourceContainment.Disposition.OS_ENFORCED,
            WorkerResourceContainment.Disposition.OS_ENFORCED,
            WorkerResourceContainment.Disposition.SUPERVISED_HARD_KILL,
            WorkerResourceContainment.Disposition.SUPERVISED_HARD_KILL,
            WorkerResourceContainment.Disposition.SUPERVISED_HARD_KILL,
            WorkerResourceContainment.Disposition.OS_ENFORCED,
            WorkerResourceContainment.Disposition.SUPERVISED_HARD_KILL,
            List.of("TEST_FIXTURE_AGGREGATE_JOB_BOUNDARY"));

    private WorkerContainmentFixtures() {
    }
}
