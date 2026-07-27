package com.minos.application;

import com.minos.adapter.scip.ScipIndexerCatalog;
import com.minos.adapter.scip.runtime.ManagedScipProviderRuntimeManager;
import com.minos.adapter.scip.runtime.ManagedScipPythonRuntimeManager;
import com.minos.adapter.scip.runtime.ScipProjectSnapshotLifecycle;
import com.minos.architecture.LocalProjectArchitectureQuery;
import com.minos.architecture.ProjectArchitectureQuery;
import com.minos.discovery.ProjectDiscoveryService;
import com.minos.git.GitIntelligenceService;
import com.minos.impact.LocalProjectImpactQuery;
import com.minos.impact.ProjectImpactQuery;
import com.minos.incremental.FileProjectFingerprintSnapshotStore;
import com.minos.incremental.IncrementalIndexingPlanner;
import com.minos.incremental.ProjectFingerprintService;
import com.minos.incremental.ProjectInvalidationService;
import com.minos.orchestration.FileIndexStateStore;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexerRegistry;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotPromoter;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotStager;
import com.minos.program.analysis.AdvancedImpactService;
import com.minos.program.analysis.ProgramGraphProvider;
import com.minos.program.analysis.ProgramGraphService;
import com.minos.program.analysis.RelationshipProgramGraphProvider;
import com.minos.program.analysis.SecurityAnalysisService;
import com.minos.registry.LocalProjectRegistry;
import com.minos.runtime.CompositeProviderRuntimeManager;
import com.minos.runtime.ProviderRuntimeManager;
import com.minos.semantic.EmbeddingProvider;
import com.minos.semantic.HybridContextBuilder;
import com.minos.semantic.HybridSearchService;
import com.minos.semantic.SemanticIndexService;
import com.minos.semantic.SemanticSearchService;
import com.minos.semantic.SemanticVectorStore;
import com.minos.store.FileSemanticVectorStore;
import com.minos.store.FileSymbolSnapshotStore;
import com.minos.workspace.WorkspaceIntelligenceService;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Long-lived local composition root for one MINOS home.
 *
 * <p>The public surfaces receive this object instead of rebuilding their own
 * registry, stores, provider runtime and query services. Provider-specific
 * implementations are confined to the default local factory; consumers only
 * see provider-neutral runtime ports and descriptors.</p>
 */
public final class MinosApplication {

    private final Path home;
    private final LocalProjectRegistry projectRegistry;
    private final FileSymbolSnapshotStore snapshotStore;
    private final FileIndexStateStore indexStateStore;
    private final FileProjectFingerprintSnapshotStore fingerprintStore;
    private final SemanticVectorStore semanticVectorStore;
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

    private MinosApplication(
            Path home,
            LocalProjectRegistry projectRegistry,
            FileSymbolSnapshotStore snapshotStore,
            FileIndexStateStore indexStateStore,
            FileProjectFingerprintSnapshotStore fingerprintStore,
            SemanticVectorStore semanticVectorStore,
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
            Optional<EmbeddingProvider> embeddingProvider
    ) {
        this.home = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
        this.projectRegistry = Objects.requireNonNull(projectRegistry, "projectRegistry");
        this.snapshotStore = Objects.requireNonNull(snapshotStore, "snapshotStore");
        this.indexStateStore = Objects.requireNonNull(indexStateStore, "indexStateStore");
        this.fingerprintStore = Objects.requireNonNull(fingerprintStore, "fingerprintStore");
        this.semanticVectorStore = Objects.requireNonNull(semanticVectorStore, "semanticVectorStore");
        this.discoveryService = Objects.requireNonNull(discoveryService, "discoveryService");
        this.fingerprintService = Objects.requireNonNull(fingerprintService, "fingerprintService");
        this.invalidationService = Objects.requireNonNull(invalidationService, "invalidationService");
        this.incrementalIndexingPlanner = Objects.requireNonNull(incrementalIndexingPlanner, "incrementalIndexingPlanner");
        this.providerRuntimeManager = Objects.requireNonNull(providerRuntimeManager, "providerRuntimeManager");
        this.indexerDescriptors = List.copyOf(Objects.requireNonNull(indexerDescriptors, "indexerDescriptors"));
        this.snapshotStager = Objects.requireNonNull(snapshotStager, "snapshotStager");
        this.snapshotPromoter = Objects.requireNonNull(snapshotPromoter, "snapshotPromoter");
        this.gitIntelligence = Objects.requireNonNull(gitIntelligence, "gitIntelligence");
        List<ProgramGraphProvider> graphProviders = List.copyOf(Objects.requireNonNull(programGraphProviders, "programGraphProviders"));
        if (graphProviders.isEmpty()) {
            throw new IllegalArgumentException("programGraphProviders must not be empty");
        }
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
        this.semanticIndexService = new SemanticIndexService(resolver, snapshotStore, semanticVectorStore, semanticProvider);
        this.semanticSearchService = new SemanticSearchService(this.semanticIndexService);
        this.hybridSearchService = new HybridSearchService(resolver, snapshotStore, this.semanticIndexService, this.semanticSearchService);
        this.hybridContextBuilder = new HybridContextBuilder(this.hybridSearchService);
        this.workspaceIntelligence = new WorkspaceIntelligenceService(projectRegistry, snapshotStore);
    }

    /** Opens MINOS with semantic embeddings disabled unless an embedding provider is explicitly configured. */
    public static MinosApplication open(Path home) throws IOException {
        return builder(home).build();
    }

    public static Builder builder(Path home) {
        return new Builder(home);
    }

    public Path home() { return home; }
    public LocalProjectRegistry projectRegistry() { return projectRegistry; }
    public FileSymbolSnapshotStore snapshotStore() { return snapshotStore; }
    public FileIndexStateStore indexStateStore() { return indexStateStore; }
    public FileProjectFingerprintSnapshotStore fingerprintStore() { return fingerprintStore; }
    public SemanticVectorStore semanticVectorStore() { return semanticVectorStore; }
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

    /** Creates a fresh negotiation registry over the application's qualified descriptors. */
    public IndexerRegistry indexerRegistry(String providerOverride) {
        IndexerRegistry registry = new IndexerRegistry();
        if (providerOverride == null || providerOverride.isBlank()) {
            registry.registerAll(indexerDescriptors);
            return registry;
        }
        IndexerDescriptor descriptor = indexerDescriptors.stream()
                .filter(candidate -> providerOverride.equals(candidate.id()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown provider override: " + providerOverride));
        registry.register(descriptor);
        return registry;
    }

    public static final class Builder {
        private final Path home;
        private LocalProjectRegistry projectRegistry;
        private FileSymbolSnapshotStore snapshotStore;
        private FileIndexStateStore indexStateStore;
        private FileProjectFingerprintSnapshotStore fingerprintStore;
        private SemanticVectorStore semanticVectorStore;
        private ProjectDiscoveryService discoveryService;
        private ProjectFingerprintService fingerprintService;
        private ProjectInvalidationService invalidationService;
        private IncrementalIndexingPlanner incrementalIndexingPlanner;
        private ProviderRuntimeManager providerRuntimeManager;
        private List<IndexerDescriptor> indexerDescriptors;
        private SnapshotStager snapshotStager;
        private SnapshotPromoter snapshotPromoter;
        private GitIntelligenceService gitIntelligence;
        private List<ProgramGraphProvider> programGraphProviders;
        private EmbeddingProvider embeddingProvider;

        private Builder(Path home) {
            this.home = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
        }

        public Builder projectRegistry(LocalProjectRegistry value) {
            this.projectRegistry = Objects.requireNonNull(value, "projectRegistry");
            return this;
        }

        public Builder snapshotStore(FileSymbolSnapshotStore value) {
            this.snapshotStore = Objects.requireNonNull(value, "snapshotStore");
            return this;
        }

        public Builder indexStateStore(FileIndexStateStore value) {
            this.indexStateStore = Objects.requireNonNull(value, "indexStateStore");
            return this;
        }

        public Builder fingerprintStore(FileProjectFingerprintSnapshotStore value) {
            this.fingerprintStore = Objects.requireNonNull(value, "fingerprintStore");
            return this;
        }

        public Builder semanticVectorStore(SemanticVectorStore value) {
            this.semanticVectorStore = Objects.requireNonNull(value, "semanticVectorStore");
            return this;
        }

        /** Opts this application instance into semantic embeddings. Default is disabled. */
        public Builder embeddingProvider(EmbeddingProvider value) {
            this.embeddingProvider = Objects.requireNonNull(value, "embeddingProvider");
            return this;
        }

        public Builder discoveryService(ProjectDiscoveryService value) {
            this.discoveryService = Objects.requireNonNull(value, "discoveryService");
            return this;
        }

        public Builder fingerprintService(ProjectFingerprintService value) {
            this.fingerprintService = Objects.requireNonNull(value, "fingerprintService");
            return this;
        }

        public Builder invalidationService(ProjectInvalidationService value) {
            this.invalidationService = Objects.requireNonNull(value, "invalidationService");
            return this;
        }

        public Builder incrementalIndexingPlanner(IncrementalIndexingPlanner value) {
            this.incrementalIndexingPlanner = Objects.requireNonNull(value, "incrementalIndexingPlanner");
            return this;
        }

        public Builder providerRuntimeManager(ProviderRuntimeManager value) {
            this.providerRuntimeManager = Objects.requireNonNull(value, "providerRuntimeManager");
            return this;
        }

        public Builder indexerDescriptors(List<IndexerDescriptor> value) {
            this.indexerDescriptors = List.copyOf(Objects.requireNonNull(value, "indexerDescriptors"));
            return this;
        }

        public Builder snapshotLifecycle(SnapshotStager stager, SnapshotPromoter promoter) {
            this.snapshotStager = Objects.requireNonNull(stager, "snapshotStager");
            this.snapshotPromoter = Objects.requireNonNull(promoter, "snapshotPromoter");
            return this;
        }

        public Builder gitIntelligence(GitIntelligenceService value) {
            this.gitIntelligence = Objects.requireNonNull(value, "gitIntelligence");
            return this;
        }

        public Builder programGraphProviders(List<ProgramGraphProvider> value) {
            this.programGraphProviders = List.copyOf(Objects.requireNonNull(value, "programGraphProviders"));
            if (this.programGraphProviders.isEmpty()) {
                throw new IllegalArgumentException("programGraphProviders must not be empty");
            }
            return this;
        }

        public MinosApplication build() throws IOException {
            LocalProjectRegistry effectiveRegistry = projectRegistry != null
                    ? projectRegistry : new LocalProjectRegistry(home.resolve("registry"));
            FileSymbolSnapshotStore effectiveSnapshots = snapshotStore != null
                    ? snapshotStore : new FileSymbolSnapshotStore(home.resolve("symbol-snapshots"));
            FileIndexStateStore effectiveIndexState = indexStateStore != null
                    ? indexStateStore : new FileIndexStateStore(home.resolve("index-state"));
            FileProjectFingerprintSnapshotStore effectiveFingerprints = fingerprintStore != null
                    ? fingerprintStore : new FileProjectFingerprintSnapshotStore(home.resolve("fingerprint-snapshots"));
            SemanticVectorStore effectiveSemanticStore = semanticVectorStore != null
                    ? semanticVectorStore : new FileSemanticVectorStore(home.resolve("semantic-index"));
            ProjectDiscoveryService effectiveDiscovery = discoveryService != null
                    ? discoveryService : new ProjectDiscoveryService();
            ProjectFingerprintService effectiveFingerprintService = fingerprintService != null
                    ? fingerprintService : new ProjectFingerprintService();
            ProjectInvalidationService effectiveInvalidation = invalidationService != null
                    ? invalidationService : new ProjectInvalidationService();
            IncrementalIndexingPlanner effectivePlanner = incrementalIndexingPlanner != null
                    ? incrementalIndexingPlanner : new IncrementalIndexingPlanner();
            List<IndexerDescriptor> effectiveDescriptors = indexerDescriptors != null
                    ? indexerDescriptors : List.copyOf(ScipIndexerCatalog.qualifiedM17Descriptors());
            ProviderRuntimeManager effectiveProviderRuntime = providerRuntimeManager != null
                    ? providerRuntimeManager
                    : new CompositeProviderRuntimeManager(List.of(
                            new ManagedScipProviderRuntimeManager(home),
                            new ManagedScipPythonRuntimeManager(home)));

            SnapshotStager effectiveStager = snapshotStager;
            SnapshotPromoter effectivePromoter = snapshotPromoter;
            if ((effectiveStager == null) != (effectivePromoter == null)) {
                throw new IllegalStateException("snapshot stager and promoter must be configured together");
            }
            if (effectiveStager == null) {
                ScipProjectSnapshotLifecycle lifecycle = new ScipProjectSnapshotLifecycle(
                        home, effectiveSnapshots, effectiveDescriptors);
                effectiveStager = lifecycle;
                effectivePromoter = lifecycle;
            }

            GitIntelligenceService effectiveGit = gitIntelligence != null
                    ? gitIntelligence : new GitIntelligenceService();
            List<ProgramGraphProvider> effectiveProgramGraphProviders = programGraphProviders != null
                    ? programGraphProviders : List.of(new RelationshipProgramGraphProvider());

            return new MinosApplication(
                    home, effectiveRegistry, effectiveSnapshots, effectiveIndexState, effectiveFingerprints,
                    effectiveSemanticStore, effectiveDiscovery, effectiveFingerprintService, effectiveInvalidation,
                    effectivePlanner, effectiveProviderRuntime, effectiveDescriptors, effectiveStager, effectivePromoter,
                    effectiveGit, effectiveProgramGraphProviders, Optional.ofNullable(embeddingProvider));
        }
    }
}
