package com.minos.runtime.local;

import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.runtime.local.StrongProcessOwnershipIndexerExecutor.Capability;
import com.minos.runtime.local.process.ProcessIndexerExecutor;
import com.minos.runtime.local.sandbox.WindowsJobObjectProcessOwnership;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Strong provider process ownership on Windows: the provider tree runs in a Job Object created by the trusted PowerShell
 * and native launcher of this installation. Windows code only, judged on Windows.
 */
final class WindowsJobObjectOwnershipBoundary implements StrongProcessOwnershipIndexerExecutor.BoundaryProvider {

    static final String MECHANISM = "windows-job-object";

    private final Path minosHome;

    WindowsJobObjectOwnershipBoundary(Path minosHome) {
        this.minosHome = Objects.requireNonNull(minosHome, "minosHome");
    }

    @Override
    public Capability capability() {
        return WindowsJobObjectProcessOwnership.discover(minosHome).isPresent()
                ? Capability.available(MECHANISM)
                : Capability.unavailable(MECHANISM, "trusted PowerShell/native Job Object launcher is unavailable");
    }

    @Override
    public ProcessIndexerExecutor.ProcessPlanTransformer transformer(IndexingExecutionRequest request) {
        return WindowsJobObjectProcessOwnership.discover(minosHome)
                .orElseThrow(() -> StrongProcessOwnershipIndexerExecutor.unavailable(
                        "Windows Job Object ownership is unavailable"))
                .transformer();
    }
}
