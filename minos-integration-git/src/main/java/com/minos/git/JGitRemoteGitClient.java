package com.minos.git;

import com.minos.remote.RemoteRepositoryRequest;
import com.minos.remote.RemoteRepositoryRequest.RemoteHost;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Default JGit transport implementation used by the remote repository materializer. */
final class JGitRemoteGitClient implements JGitRemoteRepositoryMaterializer.RemoteGitClient {

    @Override
    public void cloneRepository(
            RemoteRepositoryRequest request,
            Path destination,
            char[] secret,
            JGitRemoteRepositoryMaterializer.CloneBudget budget
    ) throws Exception {
        CloneProgressMonitor monitor = new CloneProgressMonitor(budget);
        var command = Git.cloneRepository()
                .setURI(request.canonicalRepositoryUri())
                .setDirectory(destination.toFile())
                .setBranch(request.reference())
                .setBranchesToClone(List.of(request.reference()))
                .setCloneAllBranches(false)
                .setCloneSubmodules(false)
                .setDepth(1)
                .setTimeout(budget.transportTimeoutSeconds())
                .setTransportConfigCallback(transport -> JGitCloneDeadline.configure(transport, budget))
                .setProgressMonitor(monitor);
        UsernamePasswordCredentialsProvider credentials = null;
        if (secret != null) {
            String username = request.host() == RemoteHost.GITHUB ? "x-access-token" : "oauth2";
            credentials = new UsernamePasswordCredentialsProvider(username, secret);
            command.setCredentialsProvider(credentials);
        }
        try {
            try (Git ignored = command.call()) {
                // CloneCommand has completed and resources are closed through Git.close().
            }
            budget.checkpoint();
        } catch (Exception exception) {
            IOException budgetFailure = monitor.failure();
            if (budgetFailure != null) throw budgetFailure;
            // A deadline exception raised through the HTTP proxy may be wrapped by JGit.
            // Re-checking the monotonic absolute budget maps every such failure to the stable
            // timeout contract rather than leaking transport-specific detail.
            budget.enforceTimeout();
            throw exception;
        } finally {
            if (credentials != null) credentials.clear();
        }
    }

    private static final class CloneProgressMonitor implements ProgressMonitor {
        private static final long MIN_CHECKPOINT_INTERVAL_NANOS = Duration.ofSeconds(1).toNanos();
        private final JGitRemoteRepositoryMaterializer.CloneBudget budget;
        private volatile IOException failure;
        private int updates;
        private long lastCheckpointNanos;

        private CloneProgressMonitor(JGitRemoteRepositoryMaterializer.CloneBudget budget) {
            this.budget = budget;
        }

        @Override
        public void start(int totalTasks) {
            checkpoint(true);
        }

        @Override
        public void beginTask(String title, int totalWork) {
            checkpoint(false);
        }

        @Override
        public void update(int completed) {
            updates += Math.max(1, completed);
            if (updates >= 1024) {
                updates = 0;
                checkpoint(false);
            }
        }

        @Override
        public void endTask() {
            checkpoint(true);
        }

        @Override
        public boolean isCancelled() {
            checkpoint(false);
            return failure != null;
        }

        public void showDuration(boolean enabled) {
        }

        private void checkpoint(boolean force) {
            if (failure != null) return;
            long now = System.nanoTime();
            if (!force && now - lastCheckpointNanos < MIN_CHECKPOINT_INTERVAL_NANOS) return;
            try {
                budget.checkpoint();
            } catch (IOException exception) {
                failure = exception;
            } finally {
                lastCheckpointNanos = now;
            }
        }

        private IOException failure() {
            return failure;
        }
    }
}
