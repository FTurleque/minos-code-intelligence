package com.minos.runtime.local;

import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.runtime.local.StrongProcessOwnershipIndexerExecutor.Capability;
import com.minos.runtime.local.process.CommandLocator;
import com.minos.runtime.local.process.IndexerProcessPlan;
import com.minos.runtime.local.process.ProcessIndexerExecutor;
import com.minos.runtime.local.sandbox.LinuxCgroupJob;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Strong provider process ownership on Linux: the provider is started by a trusted POSIX shell that enters a delegated
 * cgroup v2 job before it execs the provider command, so the whole tree is killed and released with the job. Linux code
 * only, judged on Linux.
 */
final class LinuxCgroupOwnershipBoundary implements StrongProcessOwnershipIndexerExecutor.BoundaryProvider {

    static final String MECHANISM = "linux-cgroup-v2";

    @Override
    public Capability capability() {
        Optional<Path> root = LinuxCgroupJob.delegatedRoot();
        Optional<Path> shell = CommandLocator.find("sh");
        if (root.isPresent() && shell.isPresent()) {
            return Capability.available(MECHANISM);
        }
        return Capability.unavailable(MECHANISM,
                root.isEmpty()
                        ? "delegated cgroup v2 root with cgroup.kill is unavailable"
                        : "trusted POSIX sh is unavailable");
    }

    @Override
    public ProcessIndexerExecutor.ProcessPlanTransformer transformer(IndexingExecutionRequest request) {
        Path root = LinuxCgroupJob.delegatedRoot()
                .orElseThrow(() -> StrongProcessOwnershipIndexerExecutor.unavailable(
                        "delegated cgroup v2 ownership is unavailable"));
        Path shell = CommandLocator.find("sh")
                .orElseThrow(() -> StrongProcessOwnershipIndexerExecutor.unavailable(
                        "trusted POSIX sh required by cgroup ownership launcher is unavailable"));
        return new CgroupTransformer(root, shell, "minos-provider-" + request.runId());
    }

    /** Single-use: one provider plan, one cgroup job, killed and released at most once each. */
    private static final class CgroupTransformer implements ProcessIndexerExecutor.ProcessPlanTransformer {
        private final Path root;
        private final Path shell;
        private final String jobName;
        private LinuxCgroupJob job;
        private RuntimeException deferredContainmentFailure;

        private CgroupTransformer(Path root, Path shell, String jobName) {
            this.root = root;
            this.shell = shell;
            this.jobName = jobName;
        }

        @Override
        public IndexerProcessPlan transform(IndexerProcessPlan plan, Path runDirectory) throws Exception {
            if (job != null) throw new IllegalStateException("process ownership transformer is single-use");
            plan.requireNoSandboxMounts("Linux cgroup process ownership without an OS sandbox");
            job = LinuxCgroupJob.createOwnershipOnly(root, jobName);
            return plan.withCommand(job.enterThenExec(shell, plan.command()));
        }

        @Override
        public synchronized void killContainedJob() {
            if (job == null) return;
            try {
                job.kill();
            } catch (RuntimeException failure) {
                rememberContainmentFailure(failure);
            }
        }

        @Override
        public synchronized void releaseContainment() {
            if (job == null) return;
            RuntimeException failure = deferredContainmentFailure;
            try {
                job.close();
            } catch (RuntimeException closeFailure) {
                if (failure == null) failure = closeFailure;
                else if (failure != closeFailure) failure.addSuppressed(closeFailure);
            } finally {
                job = null;
                deferredContainmentFailure = null;
            }
            if (failure != null) throw failure;
        }

        private void rememberContainmentFailure(RuntimeException failure) {
            if (deferredContainmentFailure == null) {
                deferredContainmentFailure = failure;
            } else if (deferredContainmentFailure != failure) {
                deferredContainmentFailure.addSuppressed(failure);
            }
        }
    }
}
