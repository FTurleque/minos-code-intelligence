package com.minos.runtime.local.wsl;

import com.minos.incremental.ProjectFingerprintService;
import com.minos.io.ConfinedFileOpener;
import com.minos.io.PrivateLocalStorage;
import com.minos.io.Sha256;
import com.minos.orchestration.IndexArtifactLimits;
import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.remote.DistributedArtifactManifest;
import com.minos.remote.DistributedIndexing.Worker;
import com.minos.remote.DistributedIndexing.WorkerNetworkPolicy;
import com.minos.remote.DistributedIndexing.WorkerRequest;
import com.minos.remote.DistributedIndexing.WorkerResponse;
import com.minos.runtime.local.distributed.DistributedArtifactBundleStore;
import com.minos.runtime.local.distributed.DistributedArtifactBundleStore.VerifiedArtifact;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import static com.minos.domain.Preconditions.requireText;

/**
 * Executor of the WSL2 route (ADR SEC-0079), returned for scip-java by the routed provider runtimes of the single
 * orchestration: it runs the provider through a {@link Worker} in the distribution and returns the artifact only
 * once the bundle is accepted by the verified store and its v3 provenance matches the request.
 *
 * <p>The accepted artifact is copied under the run directory of this installation ({@code runs/<runId>/}), where the
 * orchestration confines it like any provider artifact; the lease of the store is released and the bundle deleted
 * before {@link #execute} returns, whatever its outcome.</p>
 */
public final class WslIndexerExecutor implements IndexerExecutor {

    static final String ARTIFACT_NAME = "index.scip";

    private final String indexerId;
    private final String providerVersion;
    private final Worker worker;
    private final DistributedArtifactBundleStore bundleStore;
    private final ProjectFingerprintService fingerprints;
    private final Path runsRoot;

    /**
     * @param minosHome MINOS_HOME of this installation, whose run directory receives the verified artifact
     */
    public WslIndexerExecutor(String indexerId, String providerVersion, Worker worker,
                              DistributedArtifactBundleStore bundleStore, ProjectFingerprintService fingerprints,
                              Path minosHome) {
        this.indexerId = requireText(indexerId, "indexerId");
        this.providerVersion = requireText(providerVersion, "providerVersion");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.bundleStore = Objects.requireNonNull(bundleStore, "bundleStore");
        this.fingerprints = Objects.requireNonNull(fingerprints, "fingerprints");
        this.runsRoot = Objects.requireNonNull(minosHome, "minosHome").toAbsolutePath().normalize().resolve("runs");
    }

    @Override
    public String indexerId() {
        return indexerId;
    }

    @Override
    public IndexingArtifact execute(IndexingExecutionRequest request) throws Exception {
        Objects.requireNonNull(request, "request");
        if (!indexerId.equals(request.selection().indexer().id())) {
            throw new IllegalArgumentException("the WSL executor runs " + indexerId + " only");
        }
        String fingerprint = fingerprints.capture(request.registeredProjectRoot()).projectSha256();
        WorkerRequest workerRequest = WorkerRequest.local(request, providerVersion, fingerprint, WorkerNetworkPolicy.DENY);
        WorkerResponse response = Objects.requireNonNull(worker.execute(workerRequest), "worker response");
        VerifiedArtifact verified = null;
        IndexingArtifact artifact;
        try {
            verified = bundleStore.accept(response.bundle());
            if (!response.manifest().equals(verified.manifest())) {
                throw new IllegalStateException("worker response manifest differs from the transported manifest");
            }
            WslArtifactProvenance.verify(workerRequest, worker, indexerId, providerVersion, verified.manifest());
            Path copy = copyUnderRun(request, verified);
            String scope = verified.manifest().projectRelativeRoot();
            artifact = new IndexingArtifact(request.selection().language(), indexerId, copy, Path.of(scope));
        } catch (Exception failure) {
            releaseQuietly(verified, response.bundle(), failure);
            throw failure;
        }
        release(verified, response.bundle());
        return artifact;
    }

    /** Releases the lease and deletes the bundle; the second step runs even when the first fails. */
    private void release(VerifiedArtifact verified, Path bundle) throws IOException {
        try {
            if (verified != null) {
                bundleStore.release(verified);
            }
        } finally {
            Files.deleteIfExists(bundle);
        }
    }

    /** {@link #release} after a failure: a cleanup error is attached to it, never masks it. */
    private void releaseQuietly(VerifiedArtifact verified, Path bundle, Exception failure) {
        try {
            release(verified, bundle);
        } catch (IOException | RuntimeException cleanup) {
            failure.addSuppressed(cleanup);
        }
    }

    /**
     * Copies the verified artifact to {@code runs/<runId>/<provider>/<scope key>/index.scip}, created new and private,
     * then checks that the copy still has the verified size and SHA-256.
     */
    private Path copyUnderRun(IndexingExecutionRequest request, VerifiedArtifact verified) throws IOException {
        DistributedArtifactManifest manifest = verified.manifest();
        String scopeKey = manifest.projectRelativeRoot().isEmpty()
                ? "root" : Sha256.hex(manifest.projectRelativeRoot()).substring(0, 16);
        Path directory = runsRoot.resolve(request.runId().toString()).resolve(indexerId).resolve(scopeKey)
                .normalize();
        if (!directory.startsWith(runsRoot)) {
            throw new IllegalStateException("WSL artifact directory escapes the MINOS runs root");
        }
        PrivateLocalStorage.ensurePrivateDirectory(runsRoot);
        PrivateLocalStorage.ensurePrivateDirectory(directory.getParent().getParent());
        PrivateLocalStorage.ensurePrivateDirectory(directory.getParent());
        PrivateLocalStorage.ensurePrivateDirectory(directory);
        Path target = directory.resolve(ARTIFACT_NAME);
        // A resumed run executes the same target again in the same run directory.
        Files.deleteIfExists(target);
        long maximum = IndexArtifactLimits.MAX_SCIP_ARTIFACT_BYTES;
        try (InputStream input = ConfinedFileOpener.openRegularFileNoFollow(verified.artifact());
             OutputStream output = PrivateLocalStorage.createPrivateOutputStream(target)) {
            byte[] buffer = new byte[8192];
            long total = 0L;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maximum) {
                    throw new IOException("WSL artifact exceeds its byte limit");
                }
                output.write(buffer, 0, read);
            }
        } catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(target);
            throw failure;
        }
        if (!DistributedArtifactBundleStore.sha256Exact(target, manifest.artifactSize(), maximum)
                .equals(manifest.artifactSha256())) {
            Files.deleteIfExists(target);
            throw new IOException("WSL artifact copy does not match its verified checksum");
        }
        return target;
    }
}
