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
import java.security.MessageDigest;
import java.util.Objects;
import java.util.UUID;

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
    private final FingerprintCapture fingerprints;
    private final ArtifactOpener artifactOpener;
    private final Path runsRoot;
    private final Object fingerprintMonitor = new Object();
    private RunFingerprint lastRunFingerprint;

    /**
     * @param minosHome MINOS_HOME of this installation, whose run directory receives the verified artifact
     */
    public WslIndexerExecutor(String indexerId, String providerVersion, Worker worker,
                              DistributedArtifactBundleStore bundleStore, ProjectFingerprintService fingerprints,
                              Path minosHome) {
        this(indexerId, providerVersion, worker, bundleStore, captureWith(fingerprints),
                ConfinedFileOpener::openRegularFileNoFollow, minosHome);
    }

    /** Package-private seam: counts the fingerprint captures and the reads of the cache entry in tests. */
    WslIndexerExecutor(String indexerId, String providerVersion, Worker worker,
                       DistributedArtifactBundleStore bundleStore, FingerprintCapture fingerprints,
                       ArtifactOpener artifactOpener, Path minosHome) {
        this.indexerId = requireText(indexerId, "indexerId");
        this.providerVersion = requireText(providerVersion, "providerVersion");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.bundleStore = Objects.requireNonNull(bundleStore, "bundleStore");
        this.fingerprints = Objects.requireNonNull(fingerprints, "fingerprints");
        this.artifactOpener = Objects.requireNonNull(artifactOpener, "artifactOpener");
        this.runsRoot = Objects.requireNonNull(minosHome, "minosHome").toAbsolutePath().normalize().resolve("runs");
    }

    private static FingerprintCapture captureWith(ProjectFingerprintService service) {
        Objects.requireNonNull(service, "fingerprints");
        return root -> service.capture(root).projectSha256();
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
        String fingerprint = provenanceFingerprint(request);
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

    /**
     * The fingerprint of the registered project the provenance names: the one the run already knows, otherwise one
     * capture per run and registered root, whatever the number of targets (AUD-PERF-07).
     */
    private String provenanceFingerprint(IndexingExecutionRequest request) throws IOException {
        if (request.registeredProjectFingerprint().isPresent()) {
            return request.registeredProjectFingerprint().orElseThrow();
        }
        synchronized (fingerprintMonitor) {
            RunFingerprint last = lastRunFingerprint;
            if (last != null && last.runId().equals(request.runId())
                    && last.registeredRoot().equals(request.registeredProjectRoot())) {
                return last.fingerprint();
            }
            String captured = fingerprints.capture(request.registeredProjectRoot());
            lastRunFingerprint = new RunFingerprint(request.runId(), request.registeredProjectRoot(), captured);
            return captured;
        }
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
     * hashing the bytes while it copies them; the copy must have the verified size and SHA-256, checked once the
     * target is closed, or it is deleted. The target is created exclusively in a private directory: what it holds is
     * what was hashed.
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
        MessageDigest digest = Sha256.newDigest();
        long total = 0L;
        try (InputStream input = artifactOpener.open(verified.artifact());
             OutputStream output = PrivateLocalStorage.createPrivateOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maximum) {
                    throw new IOException("WSL artifact exceeds its byte limit");
                }
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        } catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(target);
            throw failure;
        }
        if (total != manifest.artifactSize()
                || Files.size(target) != manifest.artifactSize()
                || !Sha256.hex(digest).equals(manifest.artifactSha256())) {
            Files.deleteIfExists(target);
            throw new IOException("WSL artifact copy does not match its verified checksum");
        }
        return target;
    }

    /** Captures the SHA-256 of a registered project root. */
    @FunctionalInterface
    interface FingerprintCapture {
        String capture(Path registeredProjectRoot) throws IOException;
    }

    /** Opens the verified cache entry for reading, without following links. */
    @FunctionalInterface
    interface ArtifactOpener {
        InputStream open(Path artifact) throws IOException;
    }

    private record RunFingerprint(UUID runId, Path registeredRoot, String fingerprint) {
    }
}
