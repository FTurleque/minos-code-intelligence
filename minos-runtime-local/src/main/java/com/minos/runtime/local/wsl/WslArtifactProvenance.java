package com.minos.runtime.local.wsl;

import com.minos.remote.DistributedArtifactManifest;
import com.minos.remote.DistributedIndexing.Worker;
import com.minos.remote.DistributedIndexing.WorkerNetworkPolicy;
import com.minos.remote.DistributedIndexing.WorkerRequest;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Provenance check of the WSL route: a verified bundle is imported only when its manifest is format v3 and names
 * exactly the run, project, scope, project fingerprint, language, provider, worker and enforced {@code DENY} network
 * policy of the request. Formats v1 and v2, which name a remote revision, are refused.
 */
final class WslArtifactProvenance {

    private WslArtifactProvenance() {
    }

    /**
     * @throws IllegalStateException with a fixed message when the manifest does not match the request
     */
    static void verify(WorkerRequest request, Worker worker, String providerId, String providerVersion,
                       DistributedArtifactManifest manifest) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(worker, "worker");
        Objects.requireNonNull(manifest, "manifest");
        if (!DistributedArtifactManifest.FORMAT_V3.equals(manifest.format())) {
            throw new IllegalStateException("the WSL route accepts only artifact manifest format v3");
        }
        if (!request.local()
                || !request.execution().runId().equals(manifest.runId())
                || !request.execution().projectId().equals(manifest.projectId())
                || !portableScope(request.execution().projectRelativeRoot()).equals(manifest.projectRelativeRoot())
                || !request.projectFingerprint().equals(manifest.projectFingerprint())
                || request.execution().selection().language() != manifest.language()
                || !providerId.equals(manifest.providerId())
                || !providerVersion.equals(manifest.providerVersion())
                || !worker.workerId().equals(manifest.workerId())
                || worker.isolation() != manifest.isolation()
                || request.networkPolicy() != WorkerNetworkPolicy.DENY
                || manifest.networkPolicy() != WorkerNetworkPolicy.DENY
                || !manifest.networkDenyEnforced()) {
            throw new IllegalStateException("WSL artifact provenance does not match the indexing request");
        }
    }

    static String portableScope(Path projectRelativeRoot) {
        String portable = projectRelativeRoot.toString().replace('\\', '/');
        return ".".equals(portable) ? "" : portable;
    }
}
