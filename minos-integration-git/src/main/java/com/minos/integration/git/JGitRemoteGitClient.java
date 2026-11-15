package com.minos.integration.git;

import com.minos.remote.RemoteRepositoryRequest;
import com.minos.remote.RemoteRepositoryRequest.RemoteHost;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.TransportConfigCallback;
import org.eclipse.jgit.dircache.DirCacheCheckout;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** Default JGit transport implementation used by the remote repository materializer. */
final class JGitRemoteGitClient implements JGitRemoteRepositoryMaterializer.RemoteGitClient {

    private final TrustedRepositoryOpening opening;

    JGitRemoteGitClient() {
        this(new TrustedRepositoryOpening(null));
    }

    JGitRemoteGitClient(TrustedRepositoryOpening opening) {
        this.opening = Objects.requireNonNull(opening, "opening");
    }

    @Override
    public void cloneRepository(
            RemoteRepositoryRequest request,
            Path destination,
            char[] secret,
            JGitRemoteRepositoryMaterializer.CloneBudget budget
    ) throws Exception {
        cloneFrom(request.canonicalRepositoryUri(), request.reference(), request.host(), destination, secret, budget,
                transport -> JGitCloneDeadline.configure(transport, budget));
    }

    /**
     * Clones {@code uri} into {@code destination} and checks out its HEAD. The clone fetches the objects and
     * sets HEAD without extracting anything: the checkout runs afterwards, on the repository opened by the
     * trusted opening, so no {@code filter.*} driver of any configuration has run when the first file is
     * written. The transport callback is a parameter so that a test can clone a local repository; production
     * pins the HTTPS endpoint and the deadline through {@link JGitCloneDeadline#configure}.
     */
    void cloneFrom(
            String uri,
            String reference,
            RemoteHost host,
            Path destination,
            char[] secret,
            JGitRemoteRepositoryMaterializer.CloneBudget budget,
            TransportConfigCallback transportConfig
    ) throws Exception {
        CloneProgressMonitor monitor = new CloneProgressMonitor(budget);
        var command = Git.cloneRepository()
                .setURI(uri)
                .setDirectory(destination.toFile())
                .setBranch(reference)
                .setBranchesToClone(List.of(reference))
                .setCloneAllBranches(false)
                .setCloneSubmodules(false)
                .setDepth(1)
                .setNoCheckout(true)
                .setTimeout(budget.transportTimeoutSeconds())
                .setTransportConfigCallback(transportConfig)
                .setProgressMonitor(monitor);
        UsernamePasswordCredentialsProvider credentials = null;
        if (secret != null) {
            String username = host == RemoteHost.GITHUB ? "x-access-token" : "oauth2";
            credentials = new UsernamePasswordCredentialsProvider(username, secret);
            command.setCredentialsProvider(credentials);
        }
        try {
            try (Git ignored = command.call()) {
                // CloneCommand has completed and resources are closed through Git.close().
            }
            checkOutHead(destination, monitor);
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

    /** Writes the work tree of HEAD, the way {@code CloneCommand} does, on the repository opened under trust. */
    private void checkOutHead(Path destination, ProgressMonitor monitor) throws IOException {
        TrustedRepositoryOpening.Opened opened = opening.openExact(destination);
        try (Repository repository = opened.repository(); RevWalk walk = new RevWalk(repository)) {
            ObjectId head = repository.resolve(Constants.HEAD);
            if (head == null) {
                throw new IOException("remote ref resolved to no commit");
            }
            // The commit is parsed before the index lock is taken: a HEAD that is not a commit must not leave the
            // lock held.
            RevTree tree = walk.parseCommit(head).getTree();
            DirCacheCheckout checkout = new DirCacheCheckout(repository, repository.lockDirCache(), tree);
            checkout.setProgressMonitor(monitor);
            checkout.checkout();
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
