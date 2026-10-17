package com.minos.runtime.local.process;

import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.runtime.local.process.ProcessIndexerExecutor.ProcessPlanTransformer;

/**
 * The one place a test runs a provider process with no ownership or sandbox boundary behind it.
 *
 * <p>{@link ProcessIndexerExecutor#execute} refuses to do that, so a test that exercises the process itself
 * (digest, promotion, cleanup, write containment) names the bypass here instead of calling {@code execute}.
 * Searching for this class lists every test that runs unbounded; none of them proves a boundary.</p>
 */
public final class UnboundedProcessForTests {

    /** The plan runs as built: no cgroup, no Job Object, no sandbox. */
    public static final ProcessPlanTransformer NO_BOUNDARY = (plan, runDirectory) -> plan;

    private UnboundedProcessForTests() {
    }

    public static IndexingArtifact run(ProcessIndexerExecutor executor, IndexingExecutionRequest request)
            throws Exception {
        return executor.executeSandboxed(request, NO_BOUNDARY);
    }
}
