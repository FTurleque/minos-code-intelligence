package com.minos.remote;

import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;

import java.nio.file.Path;
import java.util.Objects;

/** Provider-neutral worker boundary used by M25 distributed execution adapters. */
public final class DistributedIndexing {

    private DistributedIndexing() {
    }

    public interface Worker {
        String workerId();

        WorkerIsolation isolation();

        boolean enforcesNetworkDeny();

        WorkerResponse execute(WorkerRequest request) throws Exception;
    }

    public record WorkerRequest(
            IndexingExecutionRequest execution,
            String providerVersion,
            String sourceRepository,
            String sourceCommit,
            WorkerNetworkPolicy networkPolicy
    ) {
        public WorkerRequest {
            Objects.requireNonNull(execution, "execution");
            providerVersion = requireText(providerVersion, "providerVersion");
            sourceRepository = requireText(sourceRepository, "sourceRepository");
            if (sourceRepository.contains("@") || sourceRepository.contains("?") || sourceRepository.contains("#")) {
                throw new IllegalArgumentException("sourceRepository must be canonical and secret-free");
            }
            if (sourceCommit == null || !sourceCommit.matches("[0-9a-f]{40}")) {
                throw new IllegalArgumentException("sourceCommit must be a lowercase complete Git SHA-1");
            }
            Objects.requireNonNull(networkPolicy, "networkPolicy");
        }
    }

    /**
     * One received transport envelope. Ownership of {@code bundle} transfers to the coordinator,
     * which must remove it after acceptance or rejection.
     */
    public record WorkerResponse(Path bundle, DistributedArtifactManifest manifest) {
        public WorkerResponse {
            bundle = Objects.requireNonNull(bundle, "bundle").toAbsolutePath().normalize();
            Objects.requireNonNull(manifest, "manifest");
        }
    }

    public enum WorkerNetworkPolicy {
        DENY,
        ALLOW
    }

    public enum WorkerIsolation {
        PROCESS_EPHEMERAL_WORKSPACE
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }
}
