package com.minos.runtime.local;

import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.runtime.local.StrongProcessOwnershipIndexerExecutor.Capability;
import com.minos.runtime.local.process.CommandLocator;
import com.minos.runtime.local.process.IndexerProcessPlan;
import com.minos.runtime.local.process.ProcessIndexerExecutor;
import com.minos.runtime.local.sandbox.LinuxCgroupJob;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Strong provider process ownership on Linux: the provider is started by a trusted POSIX shell that enters a delegated
 * cgroup v2 job before it execs the provider command, so the whole tree is killed and released with the job. Linux code
 * only, judged on Linux.
 */
final class LinuxCgroupOwnershipBoundary implements StrongProcessOwnershipIndexerExecutor.BoundaryProvider {

    static final String MECHANISM = "linux-cgroup-v2";

    private final Supplier<Optional<Path>> delegatedRoot;
    private final Supplier<Optional<Path>> trustedShell;
    private final OwnershipJobFactory jobs;

    LinuxCgroupOwnershipBoundary() {
        this(LinuxCgroupJob::delegatedRoot, () -> CommandLocator.find("sh"), LinuxCgroupOwnershipBoundary::cgroupJob);
    }

    /** Package-private seam: the delegated root, the shell and the job are given, so every refusal is tested anywhere. */
    LinuxCgroupOwnershipBoundary(Supplier<Optional<Path>> delegatedRoot, Supplier<Optional<Path>> trustedShell,
                                 OwnershipJobFactory jobs) {
        this.delegatedRoot = Objects.requireNonNull(delegatedRoot, "delegatedRoot");
        this.trustedShell = Objects.requireNonNull(trustedShell, "trustedShell");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
    }

    @Override
    public Capability capability() {
        Optional<Path> root = delegatedRoot.get();
        Optional<Path> shell = trustedShell.get();
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
        Path root = delegatedRoot.get()
                .orElseThrow(() -> StrongProcessOwnershipIndexerExecutor.unavailable(
                        "delegated cgroup v2 ownership is unavailable"));
        Path shell = trustedShell.get()
                .orElseThrow(() -> StrongProcessOwnershipIndexerExecutor.unavailable(
                        "trusted POSIX sh required by cgroup ownership launcher is unavailable"));
        return new CgroupTransformer(jobs, root, shell, "minos-provider-" + request.runId());
    }

    private static OwnershipJob cgroupJob(Path root, String name) throws IOException {
        return new CgroupJobHandle(LinuxCgroupJob.createOwnershipOnly(root, name));
    }

    /** Takes ownership of a created cgroup job: closing the handle closes the job. */
    private static final class CgroupJobHandle implements OwnershipJob {
        private final LinuxCgroupJob job;

        private CgroupJobHandle(LinuxCgroupJob job) {
            this.job = job;
        }

        @Override
        public List<String> enterThenExec(Path shell, List<String> command) {
            return job.enterThenExec(shell, command);
        }

        @Override
        public void kill() {
            job.kill();
        }

        @Override
        public void close() {
            job.close();
        }
    }

    /** The cgroup job that owns one provider tree. */
    interface OwnershipJob {
        List<String> enterThenExec(Path shell, List<String> command);

        void kill();

        void close();
    }

    /** Creates the job of one provider run under the delegated root. */
    @FunctionalInterface
    interface OwnershipJobFactory {
        OwnershipJob create(Path root, String name) throws IOException;
    }

    /** Single-use: one provider plan, one cgroup job, killed and released at most once each. */
    private static final class CgroupTransformer implements ProcessIndexerExecutor.ProcessPlanTransformer {
        private final OwnershipJobFactory jobs;
        private final Path root;
        private final Path shell;
        private final String jobName;
        private OwnershipJob job;
        private RuntimeException deferredContainmentFailure;

        private CgroupTransformer(OwnershipJobFactory jobs, Path root, Path shell, String jobName) {
            this.jobs = jobs;
            this.root = root;
            this.shell = shell;
            this.jobName = jobName;
        }

        @Override
        public IndexerProcessPlan transform(IndexerProcessPlan plan, Path runDirectory) throws Exception {
            if (job != null) throw new IllegalStateException("process ownership transformer is single-use");
            plan.requireNoSandboxMounts("Linux cgroup process ownership without an OS sandbox");
            job = jobs.create(root, jobName);
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
