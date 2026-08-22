package com.minos.application;

import com.minos.architecture.LocalProjectArchitectureQuery;
import com.minos.architecture.ProjectArchitectureQuery;
import com.minos.discovery.ProjectDiscoveryService;
import com.minos.dynamic.RuntimeIntelligenceService;
import com.minos.dynamic.RuntimeObservationStore;
import com.minos.git.GitIntelligenceService;
import com.minos.hosted.HostedControlPlaneService;
import com.minos.hosted.HostedTenantKeyProvider;
import com.minos.impact.LocalProjectImpactQuery;
import com.minos.impact.ProjectImpactQuery;
import com.minos.incremental.IncrementalIndexingPlanner;
import com.minos.incremental.ProjectFingerprintService;
import com.minos.incremental.ProjectFingerprintSnapshotStore;
import com.minos.incremental.ProjectInvalidationService;
import com.minos.io.PrivateLocalStorage;
import com.minos.orchestration.IndexStateStore;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexerRegistry;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotPromoter;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotStager;
import com.minos.program.analysis.AdvancedImpactService;
import com.minos.program.analysis.ProgramGraphProvider;
import com.minos.program.analysis.ProgramGraphService;
import com.minos.program.analysis.SecurityAnalysisService;
import com.minos.registry.ProjectRegistry;
import com.minos.runtime.ProviderRuntimeManager;
import com.minos.semantic.EmbeddingProvider;
import com.minos.semantic.HybridContextBuilder;
import com.minos.semantic.HybridSearchService;
import com.minos.semantic.SemanticIndexService;
import com.minos.semantic.SemanticSearchService;
import com.minos.semantic.SemanticVectorStore;
import com.minos.storage.MinosRuntimeSettings;
import com.minos.storage.StorageBackend;
import com.minos.storage.StorageBackendConfiguration;
import com.minos.storage.StorageBackends;
import com.minos.storage.StorageRetentionService;
import com.minos.store.CodeKnowledgeSnapshotStore;
import com.minos.workspace.WorkspaceIntelligenceService;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/** Long-lived composition root for one MINOS home and one selected storage backend. */
public final class MinosApplication implements AutoCloseable {

    public static final String SEMANTIC_PROVIDER_ENV = "MINOS_SEMANTIC_PROVIDER";
    public static final String SEMANTIC_PROVIDER_PROPERTY = "minos.semantic.provider";
    public static final String SEMANTIC_MODEL_ENV = "MINOS_SEMANTIC_MODEL";
    public static final String SEMANTIC_MODEL_PROPERTY = "minos.semantic.model";
    public static final String SEMANTIC_DIMENSIONS_ENV = "MINOS_SEMANTIC_DIMENSIONS";
    public static final String SEMANTIC_DIMENSIONS_PROPERTY = "minos.semantic.dimensions";
    public static final String SEMANTIC_ENDPOINT_ENV = "MINOS_SEMANTIC_ENDPOINT";
    public static final String SEMANTIC_ENDPOINT_PROPERTY = "minos.semantic.endpoint";
    public static final String SEMANTIC_TIMEOUT_SECONDS_ENV = "MINOS_SEMANTIC_TIMEOUT_SECONDS";
    public static final String SEMANTIC_TIMEOUT_SECONDS_PROPERTY = "minos.semantic.timeoutSeconds";
    public static final String HOSTED_MODE_ENV = "MINOS_HOSTED_MODE";
    public static final String HOSTED_MODE_PROPERTY = "minos.hosted.mode";

    private final Path home;
    private final StorageBackend storageBackend;
    private final String storageBackendId;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final ProjectRegistry projectRegistry;
    private final CodeKnowledgeSnapshotStore snapshotStore;
    private final IndexStateStore indexStateStore;
    private final ProjectFingerprintSnapshotStore fingerprintStore;
    private final SemanticVectorStore semanticVectorStore;
    private final RuntimeObservationStore runtimeObservationStore;
    private final StorageRetentionService retentionService;
    private final ProjectDiscoveryService discoveryService;
    private final ProjectFingerprintService fingerprintService;
    private final ProjectInvalidationService invalidationService;
    private final IncrementalIndexingPlanner incrementalIndexingPlanner;
    private final ProviderRuntimeManager providerRuntimeManager;
    private final List<IndexerDescriptor> indexerDescriptors;
    private final SnapshotStager snapshotStager;
    private final SnapshotPromoter snapshotPromoter;
    private final GitIntelligenceService gitIntelligence;
    private final ProjectInspectionService projectInspectionService;
    private final ProjectQueryService projectQueryService;
    private final ProjectArchitectureQuery architectureQuery;
    private final ProjectImpactQuery impactQuery;
    private final ProgramGraphService programGraphService;
    private final AdvancedImpactService advancedImpactService;
    private final SecurityAnalysisService securityAnalysisService;
    private final SemanticIndexService semanticIndexService;
    private final SemanticSearchService semanticSearchService;
    private final HybridSearchService hybridSearchService;
    private final HybridContextBuilder hybridContextBuilder;
    private final WorkspaceIntelligenceService workspaceIntelligence;
    private final RuntimeIntelligenceService runtimeIntelligenceService;
    private final Optional<HostedControlPlaneService> hostedControlPlaneService;

    MinosApplication(
            Path home,
            StorageBackend storageBackend,
            ProjectRegistry projectRegistry,
            CodeKnowledgeSnapshotStore snapshotStore,
            IndexStateStore indexStateStore,
            ProjectFingerprintSnapshotStore fingerprintStore,
            SemanticVectorStore semanticVectorStore,
            RuntimeObservationStore runtimeObservationStore,
            StorageRetentionService retentionService,
            ProjectDiscoveryService discoveryService,
            ProjectFingerprintService fingerprintService,
            ProjectInvalidationService invalidationService,
            IncrementalIndexingPlanner incrementalIndexingPlanner,
            ProviderRuntimeManager providerRuntimeManager,
            List<IndexerDescriptor> indexerDescriptors,
            SnapshotStager snapshotStager,
            SnapshotPromoter snapshotPromoter,
            GitIntelligenceService gitIntelligence,
            List<ProgramGraphProvider> programGraphProviders,
            Optional<EmbeddingProvider> embeddingProvider,
            Optional<HostedControlPlaneService> hostedControlPlaneService
    ) {
        this.home = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
        this.storageBackend = Objects.requireNonNull(storageBackend, "storageBackend");
        this.storageBackendId = requireText(storageBackend.id(), "storageBackendId");
        this.projectRegistry = Objects.requireNonNull(projectRegistry, "projectRegistry");
        this.snapshotStore = Objects.requireNonNull(snapshotStore, "snapshotStore");
        this.indexStateStore = Objects.requireNonNull(indexStateStore, "indexStateStore");
        this.fingerprintStore = Objects.requireNonNull(fingerprintStore, "fingerprintStore");
        this.semanticVectorStore = Objects.requireNonNull(semanticVectorStore, "semanticVectorStore");
        this.runtimeObservationStore = Objects.requireNonNull(runtimeObservationStore, "runtimeObservationStore");
        this.retentionService = Objects.requireNonNull(retentionService, "retentionService");
        this.discoveryService = Objects.requireNonNull(discoveryService, "discoveryService");
        this.fingerprintService = Objects.requireNonNull(fingerprintService, "fingerprintService");
        this.invalidationService = Objects.requireNonNull(invalidationService, "invalidationService");
        this.incrementalIndexingPlanner = Objects.requireNonNull(incrementalIndexingPlanner, "incrementalIndexingPlanner");
        this.providerRuntimeManager = Objects.requireNonNull(providerRuntimeManager, "providerRuntimeManager");
        this.indexerDescriptors = List.copyOf(Objects.requireNonNull(indexerDescriptors, "indexerDescriptors"));
        this.snapshotStager = Objects.requireNonNull(snapshotStager, "snapshotStager");
        this.snapshotPromoter = Objects.requireNonNull(snapshotPromoter, "snapshotPromoter");
        this.gitIntelligence = Objects.requireNonNull(gitIntelligence, "gitIntelligence");
        List<ProgramGraphProvider> graphProviders = List.copyOf(
                Objects.requireNonNull(programGraphProviders, "programGraphProviders"));
        if (graphProviders.isEmpty()) throw new IllegalArgumentException("programGraphProviders must not be empty");
        Optional<EmbeddingProvider> semanticProvider = Objects.requireNonNull(embeddingProvider, "embeddingProvider");

        this.projectInspectionService = new ProjectInspectionService(
                this.home, projectRegistry, snapshotStore, indexStateStore, discoveryService, this.indexerDescriptors);
        this.projectQueryService = new ProjectQueryService(projectRegistry, snapshotStore);
        this.architectureQuery = new LocalProjectArchitectureQuery(projectRegistry, snapshotStore, discoveryService);
        this.impactQuery = new LocalProjectImpactQuery(projectRegistry, snapshotStore);
        this.programGraphService = new ProgramGraphService(projectRegistry, snapshotStore, graphProviders);
        this.advancedImpactService = new AdvancedImpactService(this.impactQuery, this.programGraphService);
        this.securityAnalysisService = new SecurityAnalysisService(this.programGraphService);
        ProjectResolver resolver = new ProjectResolver(projectRegistry);
        this.semanticIndexService = new SemanticIndexService(
                resolver, snapshotStore, semanticVectorStore, semanticProvider);
        this.semanticSearchService = new SemanticSearchService(this.semanticIndexService);
        this.hybridSearchService = new HybridSearchService(
                resolver, snapshotStore, this.semanticIndexService, this.semanticSearchService);
        this.hybridContextBuilder = new HybridContextBuilder(this.hybridSearchService);
        this.workspaceIntelligence = new WorkspaceIntelligenceService(projectRegistry, snapshotStore);
        this.runtimeIntelligenceService = new RuntimeIntelligenceService(
                projectRegistry, snapshotStore, runtimeObservationStore);
        this.hostedControlPlaneService = Objects.requireNonNull(
                hostedControlPlaneService, "hostedControlPlaneService");
    }

    /**
     * Opens MINOS using one immutable settings snapshot for this home.
     *
     * <p>{@code home} is validated private -- rejecting a symlink, and failing closed if ownership
     * cannot be enforced or verified -- before anything reads {@code config/minos.properties}, a
     * configured secret file, or opens a storage backend. None of that may happen against a home
     * MINOS has not yet confirmed it can protect.</p>
     */
    public static MinosApplication open(Path home) throws IOException {
        Path validatedHome = PrivateLocalStorage.ensurePrivateDirectory(home);
        MinosRuntimeSettings settings = MinosRuntimeSettings.load(validatedHome);
        StorageBackend backend = StorageBackends.open(StorageBackendConfiguration.resolve(settings));
        boolean buildInvoked = false;
        try {
            Builder builder = builder(validatedHome).storageBackend(backend);
            MinosApplicationRuntimeConfiguration.apply(settings, builder);
            buildInvoked = true;
            return builder.build();
        } catch (IOException | RuntimeException exception) {
            if (!buildInvoked) closeBackendOnFailure(backend, exception);
            throw exception;
        }
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " must not be blank");
        return value;
    }

    /** Stable production composition seam retained as a regression-checkable invariant. */
    static List<ProgramGraphProvider> productionProgramGraphProviders(
            ProjectFingerprintSnapshotStore effectiveFingerprints
    ) {
        return ProgramGraphService.productionProviders(effectiveFingerprints);
    }

    public static Builder builder(Path home) { return new Builder(home); }
    public Path home() { return home; }
    public String storageBackendId() { return storageBackendId; }
    public ProjectRegistry projectRegistry() { return projectRegistry; }
    public CodeKnowledgeSnapshotStore snapshotStore() { return snapshotStore; }
    public IndexStateStore indexStateStore() { return indexStateStore; }
    public ProjectFingerprintSnapshotStore fingerprintStore() { return fingerprintStore; }
    public SemanticVectorStore semanticVectorStore() { return semanticVectorStore; }
    public RuntimeObservationStore runtimeObservationStore() { return runtimeObservationStore; }
    public StorageRetentionService retentionService() { return retentionService; }
    public ProjectDiscoveryService discoveryService() { return discoveryService; }
    public ProjectFingerprintService fingerprintService() { return fingerprintService; }
    public ProjectInvalidationService invalidationService() { return invalidationService; }
    public IncrementalIndexingPlanner incrementalIndexingPlanner() { return incrementalIndexingPlanner; }
    public ProviderRuntimeManager providerRuntimeManager() { return providerRuntimeManager; }
    public List<IndexerDescriptor> indexerDescriptors() { return indexerDescriptors; }
    public SnapshotStager snapshotStager() { return snapshotStager; }
    public SnapshotPromoter snapshotPromoter() { return snapshotPromoter; }
    public GitIntelligenceService gitIntelligence() { return gitIntelligence; }
    public ProjectInspectionService projectInspectionService() { return projectInspectionService; }
    public ProjectQueryService projectQueryService() { return projectQueryService; }
    public ProjectArchitectureQuery architectureQuery() { return architectureQuery; }
    public ProjectImpactQuery impactQuery() { return impactQuery; }
    public ProgramGraphService programGraphService() { return programGraphService; }
    public AdvancedImpactService advancedImpactService() { return advancedImpactService; }
    public SecurityAnalysisService securityAnalysisService() { return securityAnalysisService; }
    public SemanticIndexService semanticIndexService() { return semanticIndexService; }
    public SemanticSearchService semanticSearchService() { return semanticSearchService; }
    public HybridSearchService hybridSearchService() { return hybridSearchService; }
    public HybridContextBuilder hybridContextBuilder() { return hybridContextBuilder; }
    public WorkspaceIntelligenceService workspaceIntelligence() { return workspaceIntelligence; }
    public RuntimeIntelligenceService runtimeIntelligenceService() { return runtimeIntelligenceService; }
    public Optional<HostedControlPlaneService> hostedControlPlaneService() { return hostedControlPlaneService; }

    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) return;
        try {
            storageBackend.close();
        } catch (IOException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IOException("unable to close MINOS storage backend", exception);
        }
    }

    public IndexerRegistry indexerRegistry(String providerOverride) {
        IndexerRegistry registry = new IndexerRegistry();
        if (providerOverride == null || providerOverride.isBlank()) {
            registry.registerAll(indexerDescriptors);
            return registry;
        }
        IndexerDescriptor descriptor = indexerDescriptors.stream()
                .filter(candidate -> providerOverride.equals(candidate.id()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown provider override: " + providerOverride));
        registry.register(descriptor);
        return registry;
    }

    private static void closeBackendOnFailure(StorageBackend backend, Exception original) {
        try {
            backend.close();
        } catch (Exception closeFailure) {
            original.addSuppressed(closeFailure);
        }
    }

    /** Public customization surface; dependency resolution is delegated to MinosApplicationAssembler. */
    public static final class Builder {
        final Path home;
        StorageBackend storageBackend;
        ProjectRegistry projectRegistry;
        CodeKnowledgeSnapshotStore snapshotStore;
        IndexStateStore indexStateStore;
        ProjectFingerprintSnapshotStore fingerprintStore;
        SemanticVectorStore semanticVectorStore;
        RuntimeObservationStore runtimeObservationStore;
        StorageRetentionService retentionService;
        ProjectDiscoveryService discoveryService;
        ProjectFingerprintService fingerprintService;
        ProjectInvalidationService invalidationService;
        IncrementalIndexingPlanner incrementalIndexingPlanner;
        ProviderRuntimeManager providerRuntimeManager;
        List<IndexerDescriptor> indexerDescriptors;
        SnapshotStager snapshotStager;
        SnapshotPromoter snapshotPromoter;
        GitIntelligenceService gitIntelligence;
        List<ProgramGraphProvider> programGraphProviders;
        EmbeddingProvider embeddingProvider;
        HostedTenantKeyProvider hostedTenantKeyProvider;
        Clock hostedClock = Clock.systemUTC();

        private Builder(Path home) {
            this.home = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
        }

        public Builder storageBackend(StorageBackend value) {
            this.storageBackend = Objects.requireNonNull(value);
            return this;
        }

        public Builder projectRegistry(ProjectRegistry value) {
            this.projectRegistry = Objects.requireNonNull(value);
            return this;
        }

        public Builder snapshotStore(CodeKnowledgeSnapshotStore value) {
            this.snapshotStore = Objects.requireNonNull(value);
            return this;
        }

        public Builder indexStateStore(IndexStateStore value) {
            this.indexStateStore = Objects.requireNonNull(value);
            return this;
        }

        public Builder fingerprintStore(ProjectFingerprintSnapshotStore value) {
            this.fingerprintStore = Objects.requireNonNull(value);
            return this;
        }

        public Builder semanticVectorStore(SemanticVectorStore value) {
            this.semanticVectorStore = Objects.requireNonNull(value);
            return this;
        }

        public Builder runtimeObservationStore(RuntimeObservationStore value) {
            this.runtimeObservationStore = Objects.requireNonNull(value);
            return this;
        }

        public Builder retentionService(StorageRetentionService value) {
            this.retentionService = Objects.requireNonNull(value);
            return this;
        }

        public Builder embeddingProvider(EmbeddingProvider value) {
            this.embeddingProvider = Objects.requireNonNull(value);
            return this;
        }

        public Builder hostedTenantKeyProvider(HostedTenantKeyProvider value) {
            this.hostedTenantKeyProvider = Objects.requireNonNull(value);
            return this;
        }

        public Builder hostedClock(Clock value) {
            this.hostedClock = Objects.requireNonNull(value);
            return this;
        }

        public Builder discoveryService(ProjectDiscoveryService value) {
            this.discoveryService = Objects.requireNonNull(value);
            return this;
        }

        public Builder fingerprintService(ProjectFingerprintService value) {
            this.fingerprintService = Objects.requireNonNull(value);
            return this;
        }

        public Builder invalidationService(ProjectInvalidationService value) {
            this.invalidationService = Objects.requireNonNull(value);
            return this;
        }

        public Builder incrementalIndexingPlanner(IncrementalIndexingPlanner value) {
            this.incrementalIndexingPlanner = Objects.requireNonNull(value);
            return this;
        }

        public Builder providerRuntimeManager(ProviderRuntimeManager value) {
            this.providerRuntimeManager = Objects.requireNonNull(value);
            return this;
        }

        public Builder indexerDescriptors(List<IndexerDescriptor> value) {
            this.indexerDescriptors = List.copyOf(Objects.requireNonNull(value));
            return this;
        }

        public Builder snapshotLifecycle(SnapshotStager stager, SnapshotPromoter promoter) {
            this.snapshotStager = Objects.requireNonNull(stager);
            this.snapshotPromoter = Objects.requireNonNull(promoter);
            return this;
        }

        public Builder gitIntelligence(GitIntelligenceService value) {
            this.gitIntelligence = Objects.requireNonNull(value);
            return this;
        }

        public Builder programGraphProviders(List<ProgramGraphProvider> value) {
            this.programGraphProviders = List.copyOf(Objects.requireNonNull(value));
            if (this.programGraphProviders.isEmpty()) {
                throw new IllegalArgumentException("programGraphProviders must not be empty");
            }
            return this;
        }

        public MinosApplication build() throws IOException {
            return MinosApplicationAssembler.build(this);
        }
    }
}
