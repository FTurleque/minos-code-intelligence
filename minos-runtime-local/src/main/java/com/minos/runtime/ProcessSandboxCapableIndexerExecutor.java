package com.minos.runtime;

import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;

/**
 * Internal capability contract for executors whose provider process can be launched through a
 * caller-supplied, independently qualified OS sandbox boundary.
 *
 * <p>The contract is package-private on purpose: only MINOS runtime code may replace the normal
 * ownership boundary. Remote workers use it to make their stronger sandbox boundary authoritative
 * without relying on concrete executor types or nesting incompatible cgroup/Job Object owners.</p>
 */
interface ProcessSandboxCapableIndexerExecutor extends IndexerExecutor {
    IndexingArtifact executeSandboxed(
            IndexingExecutionRequest request,
            ProcessIndexerExecutor.ProcessPlanTransformer transformer
    ) throws Exception;
}
