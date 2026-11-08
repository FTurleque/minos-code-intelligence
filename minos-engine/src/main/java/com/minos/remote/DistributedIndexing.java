package com.minos.remote;

import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;

import java.nio.file.Path;
import java.util.Objects;

import static com.minos.domain.Preconditions.requireText;

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

    /**
     * What a worker executes, with the sealed {@link DistributedArtifactSource source} its artifact must name: a
     * remote revision, or the fingerprint of a local project ({@link #local}).
     */
    public record WorkerRequest(
            IndexingExecutionRequest execution,
            String providerVersion,
            DistributedArtifactSource source,
            WorkerNetworkPolicy networkPolicy
    ) {
        public WorkerRequest {
            Objects.requireNonNull(execution, "execution");
            providerVersion = requireText(providerVersion, "providerVersion");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(networkPolicy, "networkPolicy");
        }

        /** A remote request: canonical repository and complete Git commit. */
        public WorkerRequest(
                IndexingExecutionRequest execution,
                String providerVersion,
                String sourceRepository,
                String sourceCommit,
                WorkerNetworkPolicy networkPolicy
        ) {
            this(execution, providerVersion, new DistributedArtifactSource.RemoteRevision(sourceRepository, sourceCommit),
                    networkPolicy);
        }

        /** A local request: the fingerprint of the registered project, captured before the launch. */
        public static WorkerRequest local(
                IndexingExecutionRequest execution,
                String providerVersion,
                String projectFingerprint,
                WorkerNetworkPolicy networkPolicy
        ) {
            return new WorkerRequest(execution, providerVersion,
                    new DistributedArtifactSource.LocalProject(projectFingerprint), networkPolicy);
        }

        /** Whether the request names a local project rather than a remote revision. */
        public boolean local() {
            return source instanceof DistributedArtifactSource.LocalProject;
        }

        /** The repository of a remote request; a local request has none and is refused. */
        public String sourceRepository() {
            return remote().repository();
        }

        /** The commit of a remote request; a local request has none and is refused. */
        public String sourceCommit() {
            return remote().commit();
        }

        /** The project fingerprint of a local request; a remote request has none and is refused. */
        public String projectFingerprint() {
            if (source instanceof DistributedArtifactSource.LocalProject local) {
                return local.fingerprint();
            }
            throw new IllegalStateException("a remote worker request has no project fingerprint");
        }

        private DistributedArtifactSource.RemoteRevision remote() {
            if (source instanceof DistributedArtifactSource.RemoteRevision remote) {
                return remote;
            }
            throw new IllegalStateException("a local worker request has no remote revision");
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
}
