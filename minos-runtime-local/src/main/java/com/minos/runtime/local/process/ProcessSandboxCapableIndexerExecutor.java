package com.minos.runtime.local.process;

import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;

/**
 * Internal capability contract for executors whose provider process can be launched through a
 * caller-supplied, independently qualified OS sandbox boundary.
 *
 * <p>Only MINOS runtime code may replace the normal ownership boundary. The type is public so that the
 * sandbox backends of {@code com.minos.runtime.local.sandbox} can reach it; the language no longer
 * enforces that restriction, so no other module may call {@link #executeSandboxed}: a transformer
 * decides the write quota and the launcher trust of the run. Remote workers use it to make their stronger sandbox boundary authoritative
 * without relying on concrete executor types or nesting incompatible cgroup/Job Object owners.</p>
 */
public interface ProcessSandboxCapableIndexerExecutor extends IndexerExecutor {
    IndexingArtifact executeSandboxed(
            IndexingExecutionRequest request,
            ProcessIndexerExecutor.ProcessPlanTransformer transformer
    ) throws Exception;
}
