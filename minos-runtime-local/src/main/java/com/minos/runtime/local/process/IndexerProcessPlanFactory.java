package com.minos.runtime.local.process;

import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.remote.DistributedIndexing.WorkerNetworkPolicy;

import java.nio.file.Path;

/**
 * Builds the concrete command of a provider without leaking that logic into the
 * MINOS orchestration.
 */
@FunctionalInterface
public interface IndexerProcessPlanFactory {

    IndexerProcessPlan create(IndexingExecutionRequest request, Path runDirectory) throws Exception;

    /**
     * Network access granted to the complete untrusted provider descendant tree inside the
     * qualified local sandbox.
     *
     * <p>{@link WorkerNetworkPolicy#DENY} is the safe default. ALLOW is not a dependency-resolution
     * convenience: it also grants egress to repository-controlled Maven wrappers/plugins, MSBuild
     * targets, Cargo build scripts and any equivalent child process that the provider starts. A
     * factory may therefore opt into ALLOW only when its execution path is proven not to execute
     * repository-controlled code, or when that code has been separated into a distinct explicitly
     * trusted phase. Current managed SCIP factories intentionally keep DENY.</p>
     */
    default WorkerNetworkPolicy networkPolicy() {
        return WorkerNetworkPolicy.DENY;
    }
}
