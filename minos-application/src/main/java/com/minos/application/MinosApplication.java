package com.minos.application;

import com.minos.architecture.LocalProjectArchitectureQuery;
import com.minos.architecture.ProjectArchitectureQuery;
import com.minos.discovery.ProjectDiscoveryService;
import com.minos.application.dynamic.RuntimeIntelligenceService;
import com.minos.dynamic.RuntimeObservationStore;
import com.minos.git.GitIntelligence;
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
import com.minos.orchestration.IndexerProviderCatalog;
import com.minos.orchestration.ScipArtifactImporter;
import com.minos.orchestration.IndexerRegistry;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotPromoter;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotStager;
import com.minos.program.analysis.AdvancedImpactService;
import com.minos.program.analysis.ProgramGraphProvider;
import com.minos.program.analysis.ProgramGraphService;
import com.minos.program.analysis.SecurityAnalysisService;
import com.minos.registry.ProjectRegistry;
import com.minos.runtime.ProviderRuntimeManager;
import com.minos.application.semantic.EmbeddingProvider;
import com.minos.application.semantic.HybridContextBuilder;
import com.minos.application.semantic.HybridSearchService;
import com.minos.application.semantic.SemanticIndexService;
import com.minos.application.semantic.SemanticSearchService;
import com.minos.semantic.SemanticVectorStore;
import com.minos.storage.MinosRuntimeSettings;
import com.minos.storage.StorageBackend;
import com.minos.storage.StorageBackendConfiguration;
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
    private final Stores stores;
    private final Indexing indexing;
    /** Defensive copy of {@code indexing.indexerDescriptors()}, taken by the constructor as before. */
    private final List<IndexerDescriptor> indexerDescriptors;
    private final GitIntelligence gitIntelligence;
    private final Queries queries;
    private final Semantic semantic;
    private final Optional<HostedControlPlaneService> hostedControlPlaneService;
    private final MinosApplicationComposer compositionRoot;

    /**
     * Stores resolved by the assembler (builder override or selected backend), grouped by domain (ADR 0045).
     * A plain carrier: no validation, no instance creation; the constructor validates in the historical order.
     */
    record Stores(
            ProjectRegistry projectRegistry,
            CodeKnowledgeSnapshotStore snapshotStore,
            IndexStateStore indexStateStore,
            ProjectFingerprintSnapshotStore fingerprintStore,
            SemanticVectorStore semanticVectorStore,
            RuntimeObservationStore runtimeObservationStore,
            StorageRetentionService retentionService
    ) {
    }

    /** Indexing collaborators resolved by the assembler, grouped by domain (ADR 0045); plain carrier like {@link Stores}. */
    record Indexing(
            ProjectDiscoveryService discoveryService,
            ProjectFingerprintService fingerprintService,
            ProjectInvalidationService invalidationService,
            IncrementalIndexingPlanner incrementalIndexingPlanner,
            ProviderRuntimeManager providerRuntimeManager,
            List<IndexerDescriptor> indexerDescriptors,
            IndexerProviderCatalog providerCatalog,
            ScipArtifactImporter scipArtifactImporter,
            SnapshotStager snapshotStager,
            SnapshotPromoter snapshotPromoter
    ) {
    }

    /** Query services derived by the constructor, in construction order. */
    private record Queries(
            ProjectInspectionService projectInspectionService,
            ProjectQueryService projectQueryService,
            ProjectArchitectureQuery architectureQuery,
            ProjectImpactQuery impactQuery,
            ProgramGraphService programGraphService,
            AdvancedImpactService advancedImpactService,
            SecurityAnalysisService securityAnalysisService,
            WorkspaceIntelligenceService workspaceIntelligence,
            RuntimeIntelligenceService runtimeIntelligenceService
    ) {
    }

    /** Semantic and hybrid services derived by the constructor; index and hybrid search share one resolver. */
    private record Semantic(
            SemanticIndexService semanticIndexService,
            SemanticSearchService semanticSearchService,
            HybridSearchService hybridSearchService,
            HybridContextBuilder hybridContextBuilder
    ) {
    }

    MinosApplication(
            Path home,
            StorageBackend storageBackend,
            Stores stores,
            Indexing indexing,
            GitIntelligence gitIntelligence,
            List<ProgramGraphProvider> programGraphProviders,
            Optional<EmbeddingProvider> embeddingProvider,
            Optional<HostedControlPlaneService> hostedControlPlaneService,
            MinosApplicationComposer compositionRoot
    ) {
        this.home = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
        this.storageBackend = Objects.requireNonNull(storageBackend, "storageBackend");
        this.storageBackendId = requireText(storageBackend.id(), "storageBackendId");
        this.stores = Objects.requireNonNull(stores, "stores");
        ProjectRegistry projectRegistry = Objects.requireNonNull(stores.projectRegistry(), "projectRegistry");
        CodeKnowledgeSnapshotStore snapshotStore = Objects.requireNonNull(stores.snapshotStore(), "snapshotStore");
        IndexStateStore indexStateStore = Objects.requireNonNull(stores.indexStateStore(), "indexStateStore");
        Objects.requireNonNull(stores.fingerprintStore(), "fingerprintStore");
        SemanticVectorStore semanticVectorStore =
                Objects.requireNonNull(stores.semanticVectorStore(), "semanticVectorStore");
        RuntimeObservationStore runtimeObservationStore =
                Objects.requireNonNull(stores.runtimeObservationStore(), "runtimeObservationStore");
        Objects.requireNonNull(stores.retentionService(), "retentionService");
        this.indexing = Objects.requireNonNull(indexing, "indexing");
        ProjectDiscoveryService discoveryService =
                Objects.requireNonNull(indexing.discoveryService(), "discoveryService");
        Objects.requireNonNull(indexing.fingerprintService(), "fingerprintService");
        Objects.requireNonNull(indexing.invalidationService(), "invalidationService");
        Objects.requireNonNull(indexing.incrementalIndexingPlanner(), "incrementalIndexingPlanner");
        Objects.requireNonNull(indexing.providerRuntimeManager(), "providerRuntimeManager");
        this.indexerDescriptors = List.copyOf(Objects.requireNonNull(indexing.indexerDescriptors(), "indexerDescriptors"));
        Objects.requireNonNull(indexing.providerCatalog(), "providerCatalog");
        Objects.requireNonNull(indexing.scipArtifactImporter(), "scipArtifactImporter");
        Objects.requireNonNull(indexing.snapshotStager(), "snapshotStager");
        Objects.requireNonNull(indexing.snapshotPromoter(), "snapshotPromoter");
        this.gitIntelligence = Objects.requireNonNull(gitIntelligence, "gitIntelligence");
        List<ProgramGraphProvider> graphProviders = List.copyOf(
                Objects.requireNonNull(programGraphProviders, "programGraphProviders"));
        if (graphProviders.isEmpty()) throw new IllegalArgumentException("programGraphProviders must not be empty");
        Optional<EmbeddingProvider> semanticProvider = Objects.requireNonNull(embeddingProvider, "embeddingProvider");

        ProjectInspectionService projectInspectionService = new ProjectInspectionService(
                this.home, projectRegistry, snapshotStore, indexStateStore, discoveryService, this.indexerDescriptors);
        ProjectQueryService projectQueryService = new ProjectQueryService(projectRegistry, snapshotStore);
        ProjectArchitectureQuery architectureQuery =
                LocalProjectArchitectureQuery.defaults(projectRegistry, snapshotStore, discoveryService);
        ProjectImpactQuery impactQuery = new LocalProjectImpactQuery(projectRegistry, snapshotStore);
        ProgramGraphService programGraphService = new ProgramGraphService(projectRegistry, snapshotStore, graphProviders);
        AdvancedImpactService advancedImpactService = new AdvancedImpactService(impactQuery, programGraphService);
        SecurityAnalysisService securityAnalysisService = new SecurityAnalysisService(programGraphService);
        ProjectResolver resolver = new ProjectResolver(projectRegistry);
        SemanticIndexService semanticIndexService = new SemanticIndexService(
                resolver, snapshotStore, semanticVectorStore, semanticProvider);
        SemanticSearchService semanticSearchService = new SemanticSearchService(semanticIndexService);
        HybridSearchService hybridSearchService = new HybridSearchService(
                resolver, snapshotStore, semanticIndexService, semanticSearchService);
        HybridContextBuilder hybridContextBuilder = new HybridContextBuilder(hybridSearchService);
        WorkspaceIntelligenceService workspaceIntelligence = new WorkspaceIntelligenceService(projectRegistry, snapshotStore);
        RuntimeIntelligenceService runtimeIntelligenceService = new RuntimeIntelligenceService(
                projectRegistry, snapshotStore, runtimeObservationStore);
        this.queries = new Queries(projectInspectionService, projectQueryService, architectureQuery, impactQuery,
                programGraphService, advancedImpactService, securityAnalysisService, workspaceIntelligence,
                runtimeIntelligenceService);
        this.semantic = new Semantic(
                semanticIndexService, semanticSearchService, hybridSearchService, hybridContextBuilder);
        this.hostedControlPlaneService = Objects.requireNonNull(
                hostedControlPlaneService, "hostedControlPlaneService");
        this.compositionRoot = Objects.requireNonNull(compositionRoot, "compositionRoot");
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
        // ADR 0042 : la racine de composition est résolue d'abord, sans effet de bord ; absente ou
        // ambiguë, l'ouverture échoue avant de toucher au MINOS_HOME.
        MinosApplicationComposer composer = MinosApplicationComposers.resolve();
        Path validatedHome = PrivateLocalStorage.ensurePrivateDirectory(home);
        MinosRuntimeSettings settings = MinosRuntimeSettings.load(validatedHome);
        StorageBackend backend = composer.openStorageBackend(StorageBackendConfiguration.resolve(settings));
        boolean buildInvoked = false;
        try {
            Builder builder = builder(validatedHome).storageBackend(backend);
            builder.composer = composer;
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

    // Identity and composition.
    public Path home() { return home; }
    public String storageBackendId() { return storageBackendId; }

    /**
     * Racine de composition qui a construit cette application (ADR 0042) : les surfaces lui demandent
     * les adaptateurs qu'elles créent elles-mêmes, au moment où elles les créaient.
     */
    public MinosApplicationComposer compositionRoot() { return compositionRoot; }

    // Storage (ADR 0045: grouped in Stores).
    public ProjectRegistry projectRegistry() { return stores.projectRegistry(); }
    public CodeKnowledgeSnapshotStore snapshotStore() { return stores.snapshotStore(); }
    public IndexStateStore indexStateStore() { return stores.indexStateStore(); }
    public ProjectFingerprintSnapshotStore fingerprintStore() { return stores.fingerprintStore(); }
    public SemanticVectorStore semanticVectorStore() { return stores.semanticVectorStore(); }
    public RuntimeObservationStore runtimeObservationStore() { return stores.runtimeObservationStore(); }
    public StorageRetentionService retentionService() { return stores.retentionService(); }

    // Indexing (ADR 0045: grouped in Indexing).
    public ProjectDiscoveryService discoveryService() { return indexing.discoveryService(); }
    public ProjectFingerprintService fingerprintService() { return indexing.fingerprintService(); }
    public ProjectInvalidationService invalidationService() { return indexing.invalidationService(); }
    public IncrementalIndexingPlanner incrementalIndexingPlanner() { return indexing.incrementalIndexingPlanner(); }
    public ProviderRuntimeManager providerRuntimeManager() { return indexing.providerRuntimeManager(); }
    public List<IndexerDescriptor> indexerDescriptors() { return indexerDescriptors; }
    /** Port du catalogue de providers ; consulté à la demande, jamais figé à la composition. */
    public IndexerProviderCatalog providerCatalog() { return indexing.providerCatalog(); }
    /** Port d'import SCIP explicite (import-scip) ; l'adaptateur est instancié à chaque import. */
    public ScipArtifactImporter scipArtifactImporter() { return indexing.scipArtifactImporter(); }
    public SnapshotStager snapshotStager() { return indexing.snapshotStager(); }
    public SnapshotPromoter snapshotPromoter() { return indexing.snapshotPromoter(); }

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

    // Queries (ADR 0045: derived services grouped in Queries).
    public GitIntelligence gitIntelligence() { return gitIntelligence; }
    public ProjectInspectionService projectInspectionService() { return queries.projectInspectionService(); }
    public ProjectQueryService projectQueryService() { return queries.projectQueryService(); }
    public ProjectArchitectureQuery architectureQuery() { return queries.architectureQuery(); }
    public ProjectImpactQuery impactQuery() { return queries.impactQuery(); }
    public ProgramGraphService programGraphService() { return queries.programGraphService(); }
    public AdvancedImpactService advancedImpactService() { return queries.advancedImpactService(); }
    public SecurityAnalysisService securityAnalysisService() { return queries.securityAnalysisService(); }
    public WorkspaceIntelligenceService workspaceIntelligence() { return queries.workspaceIntelligence(); }
    public RuntimeIntelligenceService runtimeIntelligenceService() { return queries.runtimeIntelligenceService(); }

    // Semantic and hybrid retrieval (ADR 0045: derived services grouped in Semantic).
    public SemanticIndexService semanticIndexService() { return semantic.semanticIndexService(); }
    public SemanticSearchService semanticSearchService() { return semantic.semanticSearchService(); }
    public HybridSearchService hybridSearchService() { return semantic.hybridSearchService(); }
    public HybridContextBuilder hybridContextBuilder() { return semantic.hybridContextBuilder(); }

    // Hosted team control plane (opt-in).
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
        IndexerProviderCatalog providerCatalog;
        ScipArtifactImporter scipArtifactImporter;
        SnapshotStager snapshotStager;
        SnapshotPromoter snapshotPromoter;
        GitIntelligence gitIntelligence;
        List<ProgramGraphProvider> programGraphProviders;
        EmbeddingProvider embeddingProvider;
        HostedTenantKeyProvider hostedTenantKeyProvider;
        Clock hostedClock = Clock.systemUTC();
        /** Racine de composition résolue une fois par construction (ADR 0042). */
        MinosApplicationComposer composer;

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

        public Builder providerCatalog(IndexerProviderCatalog value) {
            this.providerCatalog = Objects.requireNonNull(value);
            return this;
        }

        public Builder scipArtifactImporter(ScipArtifactImporter value) {
            this.scipArtifactImporter = Objects.requireNonNull(value);
            return this;
        }

        public Builder snapshotLifecycle(SnapshotStager stager, SnapshotPromoter promoter) {
            this.snapshotStager = Objects.requireNonNull(stager);
            this.snapshotPromoter = Objects.requireNonNull(promoter);
            return this;
        }

        public Builder gitIntelligence(GitIntelligence value) {
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

        MinosApplicationComposer resolvedComposer() {
            if (composer == null) composer = MinosApplicationComposers.resolve();
            return composer;
        }
    }
}
