package com.minos.application.remote;

import com.minos.application.indexing.IndexExecutionSummary;
import com.minos.application.indexing.IndexPlanSummary;
import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexerCapability;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexerQualification;
import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.registry.ProjectRegistry;
import com.minos.registry.RegisteredProject;
import com.minos.registry.RegisteredWorkspace;
import com.minos.remote.DistributedArtifactManifest;
import com.minos.remote.DistributedIndexing.WorkerIsolation;
import com.minos.remote.DistributedIndexing.WorkerNetworkPolicy;
import com.minos.remote.RemoteIndexingRuntime;
import com.minos.remote.RemoteIndexingRuntime.DistributedExecution;
import com.minos.remote.RemoteIndexingRuntime.VerifiedArtifactEvidence;
import com.minos.remote.RemoteRepositoryMaterializer;
import com.minos.remote.RemoteRepositoryMaterializer.RemoteMaterialization;
import com.minos.remote.RemoteRepositoryRequest;
import com.minos.runtime.WorkerSandboxProbe;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** In-memory ports of the remote indexing use case: no adapter, no worker, no class of a surface. */
final class RemoteIndexingFixture {
    static final String PROVIDER = "scip-java";
    static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";
    static final String BACKEND = "native-process-ephemeral-workspace-v1";
    static final String REFUSAL = "untrusted remote execution is fail-closed by decision (ADR 0041); OS sandbox backend "
            + "windows-appcontainer-job-v3 was rejected for untrusted code: "
            + "FILESYSTEM_WRITE_BYTES_REQUIRES_OS_ENFORCED_JOB_BOUNDARY_BUT_IS_SUPERVISED_HARD_KILL";

    final Path home;
    final Path repositoryRoot;
    final Materializer materializer = new Materializer();
    final Registry registry = new Registry();
    final Runtime runtime = new Runtime();
    final List<IndexerDescriptor> descriptors = new ArrayList<>(List.of(descriptor(PROVIDER)));
    /** The providers the local indexing executes; empty means every descriptor. */
    List<String> executedProviders = List.of();
    /** What the local indexing does after it decorated the provider executor; it may throw. */
    IndexingStep indexingStep = () -> { };
    final AtomicInteger indexings = new AtomicInteger();

    @FunctionalInterface
    interface IndexingStep {
        void run() throws IOException;
    }

    RemoteIndexingFixture(Path temp) throws IOException {
        this.home = temp.resolve("home");
        this.repositoryRoot = Files.createDirectories(temp.resolve("remote-cache").resolve("acme-demo"));
    }

    RemoteIndexingUseCase useCase() {
        return new RemoteIndexingUseCase(new RemoteIndexingUseCase.Dependencies(home, registry, materializer, runtime,
                descriptors, (projectId, providerOverride, decorator) -> {
                    indexings.incrementAndGet();
                    List<String> providers = executedProviders.isEmpty()
                            ? descriptors.stream().map(IndexerDescriptor::id).toList() : executedProviders;
                    for (String provider : providers) {
                        decorator.apply(new LocalExecutor(provider));
                    }
                    indexingStep.run();
                    return execution(projectId);
                }));
    }

    static RemoteSourceRequest request() {
        return RemoteSourceRequest.of("https://github.com/acme/demo", "main", COMMIT, null, null);
    }

    static IndexerDescriptor descriptor(String id) {
        return new IndexerDescriptor(id, "0.10.0", id, Set.of(Language.JAVA), Set.of(),
                EnumSet.of(IndexerCapability.SYMBOLS), IndexerQualification.QUALIFIED, 100, List.of());
    }

    static IndexExecutionSummary execution(String projectId) {
        IndexPlanSummary plan = new IndexPlanSummary(projectId, "demo", "/remote/demo", List.of("JAVA"), List.of(),
                List.of(PROVIDER), List.of(), "FULL", List.of("FORCED_FULL"), List.of(), true);
        return new IndexExecutionSummary(plan, Optional.of(UUID.randomUUID().toString()), "SUCCEEDED",
                Optional.of("snapshot-1"), true, Optional.empty(), Optional.empty());
    }

    static WorkerSandboxProbe.UntrustedCodeSandbox selection(boolean qualified) {
        return new WorkerSandboxProbe.UntrustedCodeSandbox(BACKEND, qualified,
                qualified ? "QUALIFIED" : WorkerSandboxProbe.CAUSE_REJECTED_BY_DECISION,
                qualified ? Optional.empty() : Optional.of("windows-appcontainer-job-v3"),
                List.of(), qualified ? "" : REFUSAL);
    }

    /** A materializer that records each call; the delegate of the idempotent wrapper of the use case. */
    final class Materializer implements RemoteRepositoryMaterializer {
        final AtomicInteger materialized = new AtomicInteger();
        final AtomicInteger pinned = new AtomicInteger();
        final AtomicInteger unpinned = new AtomicInteger();
        final AtomicInteger released = new AtomicInteger();
        Exception materializeFailure;
        Exception pinFailure;
        Exception releaseFailure;

        @Override
        public RemoteMaterialization materialize(RemoteRepositoryRequest request) throws Exception {
            materialized.incrementAndGet();
            if (materializeFailure != null) throw materializeFailure;
            return new RemoteMaterialization(request, repositoryRoot, repositoryRoot, "cache-key-1", false,
                    Instant.parse("2026-10-23T08:00:00Z"));
        }

        @Override
        public void pin(RemoteMaterialization materialization) throws Exception {
            pinned.incrementAndGet();
            if (pinFailure != null) throw pinFailure;
        }

        @Override
        public void unpin(RemoteMaterialization materialization) {
            unpinned.incrementAndGet();
        }

        @Override
        public void release(RemoteMaterialization materialization) throws Exception {
            released.incrementAndGet();
            if (releaseFailure != null) throw releaseFailure;
        }
    }

    /** A registry of projects in memory; registration can be made to fail or to find the project already there. */
    static final class Registry implements ProjectRegistry {
        final Map<UUID, RegisteredProject> projects = new LinkedHashMap<>();
        IOException registrationFailure;
        boolean alreadyRegistered;

        @Override
        public RegisteredProject registerProject(Path rootPath, String displayName) throws IOException {
            return registerProjectWithResult(rootPath, displayName).project();
        }

        @Override
        public RegistrationResult registerProjectWithResult(Path rootPath, String displayName) throws IOException {
            if (registrationFailure != null) throw registrationFailure;
            Instant now = Instant.parse("2026-10-23T08:00:00Z");
            RegisteredProject project = new RegisteredProject(UUID.randomUUID(), rootPath, displayName,
                    Optional.empty(), now, now);
            projects.put(project.id(), project);
            return new RegistrationResult(project, !alreadyRegistered);
        }

        @Override
        public boolean deleteProject(UUID projectId) {
            return projects.remove(projectId) != null;
        }

        @Override public Optional<RegisteredProject> findProject(UUID projectId) {
            return Optional.ofNullable(projects.get(projectId));
        }
        @Override public List<RegisteredProject> listProjects() { return List.copyOf(projects.values()); }
        @Override public RegisteredWorkspace createWorkspace(String name) { throw unused(); }
        @Override public RegisteredProject assignProjectToWorkspace(UUID projectId, UUID workspaceId) { throw unused(); }
        @Override public RegisteredProject removeProjectFromWorkspace(UUID projectId) { throw unused(); }
        @Override public Optional<RegisteredWorkspace> findWorkspace(UUID workspaceId) { return Optional.empty(); }
        @Override public List<RegisteredWorkspace> listWorkspaces() { return List.of(); }

        private static UnsupportedOperationException unused() {
            return new UnsupportedOperationException("workspaces are not used by the remote indexing");
        }
    }

    /** A runtime whose sandbox selection and distributed executions the test chooses. */
    static final class Runtime implements RemoteIndexingRuntime {
        WorkerSandboxProbe.UntrustedCodeSandbox selection = selection(true);
        final List<Distributed> distributed = new ArrayList<>();
        final List<WorkerNetworkPolicy> policies = new ArrayList<>();
        /** Evidence each distributed execution reports, by provider. */
        final Map<String, List<VerifiedArtifactEvidence>> evidence = new LinkedHashMap<>();
        IOException closeFailure;

        @Override
        public WorkerSandboxProbe.UntrustedCodeSandbox untrustedCodeSandbox() {
            return selection;
        }

        @Override
        public DistributedExecution distribute(String providerId, String providerVersion, RemoteMaterialization source,
                                               WorkerNetworkPolicy workerNetworkPolicy, String workerId,
                                               IndexerExecutor delegate) {
            policies.add(workerNetworkPolicy);
            Distributed execution = new Distributed(delegate, evidence.getOrDefault(providerId, List.of()), closeFailure);
            distributed.add(execution);
            return execution;
        }
    }

    static final class Distributed implements DistributedExecution {
        private final IndexerExecutor delegate;
        private final List<VerifiedArtifactEvidence> verified;
        private final IOException closeFailure;
        final AtomicInteger closed = new AtomicInteger();

        Distributed(IndexerExecutor delegate, List<VerifiedArtifactEvidence> verified, IOException closeFailure) {
            this.delegate = delegate;
            this.verified = List.copyOf(verified);
            this.closeFailure = closeFailure;
        }

        @Override public String indexerId() { return delegate.indexerId(); }
        @Override public IndexingArtifact execute(IndexingExecutionRequest request) throws Exception {
            return delegate.execute(request);
        }
        @Override public List<VerifiedArtifactEvidence> verifiedArtifacts() { return verified; }
        @Override public void close() throws IOException {
            closed.incrementAndGet();
            if (closeFailure != null) throw closeFailure;
        }
    }

    record LocalExecutor(String indexerId) implements IndexerExecutor {
        @Override
        public IndexingArtifact execute(IndexingExecutionRequest request) {
            throw new UnsupportedOperationException("the fixture never executes a provider");
        }
    }

    static VerifiedArtifactEvidence evidence(String providerId, String projectRelativeRoot) {
        DistributedArtifactManifest manifest = new DistributedArtifactManifest(
                DistributedArtifactManifest.FORMAT_V2, UUID.randomUUID(), UUID.randomUUID(), projectRelativeRoot,
                "https://github.com/acme/demo.git", COMMIT, Language.JAVA, providerId, "0.10.0", "worker-one",
                WorkerIsolation.PROCESS_EPHEMERAL_WORKSPACE, WorkerNetworkPolicy.ALLOW, false,
                Instant.parse("2026-10-23T08:00:00Z"), Instant.parse("2026-10-23T08:01:00Z"),
                DistributedArtifactManifest.ARTIFACT_PATH, 12L, "a".repeat(64));
        return new VerifiedArtifactEvidence(manifest, "b".repeat(64), "artifact-cache-" + projectRelativeRoot, false);
    }
}
