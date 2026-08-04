package com.minos.application;

import com.minos.adapter.scip.ScipIndexerCatalog;
import com.minos.adapter.scip.runtime.ManagedPolyglotScipRuntimeManager;
import com.minos.adapter.scip.runtime.ManagedScipProviderRuntimeManager;
import com.minos.adapter.scip.runtime.ManagedScipPythonRuntimeManager;
import com.minos.adapter.scip.runtime.ScipProjectSnapshotLifecycle;
import com.minos.architecture.LocalProjectArchitectureQuery;
import com.minos.architecture.ProjectArchitectureQuery;
import com.minos.discovery.ProjectDiscoveryService;
import com.minos.dynamic.RuntimeIntelligenceService;
import com.minos.dynamic.RuntimeObservationStore;
import com.minos.git.GitIntelligenceService;
import com.minos.hosted.HmacHostedIdentityProvider;
import com.minos.hosted.HostedControlPlaneService;
import com.minos.hosted.HostedTenantKeyProvider;
import com.minos.impact.LocalProjectImpactQuery;
import com.minos.impact.ProjectImpactQuery;
import com.minos.incremental.IncrementalIndexingPlanner;
import com.minos.incremental.ProjectFingerprintService;
import com.minos.incremental.ProjectFingerprintSnapshotStore;
import com.minos.incremental.ProjectInvalidationService;
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
import com.minos.runtime.CompositeProviderRuntimeManager;
import com.minos.runtime.ProviderRuntimeManager;
import com.minos.semantic.EmbeddingProvider;
import com.minos.semantic.HybridContextBuilder;
import com.minos.semantic.HybridSearchService;
import com.minos.semantic.LocalHashEmbeddingProvider;
import com.minos.semantic.OllamaEmbeddingProvider;
import com.minos.semantic.SemanticIndexService;
import com.minos.semantic.SemanticSearchService;
import com.minos.semantic.SemanticVectorStore;
import com.minos.storage.StorageBackend;
import com.minos.storage.StorageBackendConfiguration;
import com.minos.storage.StorageBackends;
import com.minos.store.CodeKnowledgeSnapshotStore;
import com.minos.store.EnvironmentHostedTenantKeyProvider;
import com.minos.store.FileHostedControlPlaneStore;
import com.minos.workspace.WorkspaceIntelligenceService;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Long-lived composition root for one MINOS home and one selected storage backend. */
public final class MinosApplication {

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
    private final String storageBackendId;
    private final ProjectRegistry projectRegistry;
    private final CodeKnowledgeSnapshotStore snapshotStore;
    private final IndexStateStore indexStateStore;
    private final ProjectFingerprintSnapshotStore fingerprintStore;
    private final SemanticVectorStore semanticVectorStore;
    private final RuntimeObservationStore runtimeObservationStore;
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

    private MinosApplication(
            Path home,
            String storageBackendId,
            ProjectRegistry projectRegistry,
            CodeKnowledgeSnapshotStore snapshotStore,
            IndexStateStore indexStateStore,
            ProjectFingerprintSnapshotStore fingerprintStore,
            SemanticVectorStore semanticVectorStore,
            RuntimeObservationStore runtimeObservationStore,
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
        this.storageBackendId = requireText(storageBackendId, "storageBackendId");
        this.projectRegistry = Objects.requireNonNull(projectRegistry, "projectRegistry");
        this.snapshotStore = Objects.requireNonNull(snapshotStore, "snapshotStore");
        this.indexStateStore = Objects.requireNonNull(indexStateStore, "indexStateStore");
        this.fingerprintStore = Objects.requireNonNull(fingerprintStore, "fingerprintStore");
        this.semanticVectorStore = Objects.requireNonNull(semanticVectorStore, "semanticVectorStore");
        this.runtimeObservationStore = Objects.requireNonNull(runtimeObservationStore, "runtimeObservationStore");
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
        if (graphProviders.isEmpty()) throw new IllegalArgumentException("programGraphProviders must not be empty");
        Optional<EmbeddingProvider> semanticProvider = Objects.requireNonNull(embeddingProvider, "embeddingProvider");

        this.projectInspectionService = new ProjectInspectionService(this.home, projectRegistry, snapshotStore,
                indexStateStore, discoveryService, this.indexerDescriptors);
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
        this.runtimeIntelligenceService = new RuntimeIntelligenceService(projectRegistry, snapshotStore, runtimeObservationStore);
        this.hostedControlPlaneService = Objects.requireNonNull(hostedControlPlaneService, "hostedControlPlaneService");
    }

    /** Opens MINOS using storage and semantic settings from system properties/environment. */
    public static MinosApplication open(Path home) throws IOException {
        Builder builder = builder(home).storageBackend(StorageBackends.open(StorageBackendConfiguration.resolve(home)));
        String configured = setting(SEMANTIC_PROVIDER_PROPERTY, SEMANTIC_PROVIDER_ENV);
        String provider = configured == null || configured.isBlank() ? "disabled" : configured.trim().toLowerCase(Locale.ROOT);
        if ("local-hash".equals(provider)) {
            builder.embeddingProvider(new LocalHashEmbeddingProvider());
        } else if ("ollama".equals(provider) || "local-ollama".equals(provider)) {
            String model = requiredSetting(SEMANTIC_MODEL_PROPERTY, SEMANTIC_MODEL_ENV);
            int dimensions = parsePositiveInt(requiredSetting(SEMANTIC_DIMENSIONS_PROPERTY, SEMANTIC_DIMENSIONS_ENV), "semantic dimensions");
            String endpointValue = setting(SEMANTIC_ENDPOINT_PROPERTY, SEMANTIC_ENDPOINT_ENV);
            URI endpoint = endpointValue == null || endpointValue.isBlank() ? OllamaEmbeddingProvider.DEFAULT_ENDPOINT : URI.create(endpointValue.trim());
            String timeoutValue = setting(SEMANTIC_TIMEOUT_SECONDS_PROPERTY, SEMANTIC_TIMEOUT_SECONDS_ENV);
            Duration timeout = timeoutValue == null || timeoutValue.isBlank() ? OllamaEmbeddingProvider.DEFAULT_TIMEOUT
                    : Duration.ofSeconds(parsePositiveInt(timeoutValue, "semantic timeout seconds"));
            builder.embeddingProvider(new OllamaEmbeddingProvider(endpoint, model, dimensions, timeout));
        } else if (!"disabled".equals(provider)) {
            throw new IllegalArgumentException("unsupported semantic provider: " + configured);
        }
        String hostedMode = setting(HOSTED_MODE_PROPERTY, HOSTED_MODE_ENV);
        if (hostedMode != null && !hostedMode.isBlank() && !"disabled".equalsIgnoreCase(hostedMode)) {
            if (!"enabled".equalsIgnoreCase(hostedMode)) throw new IllegalArgumentException("unsupported hosted mode: " + hostedMode);
            builder.hostedTenantKeyProvider(new EnvironmentHostedTenantKeyProvider());
        }
        return builder.build();
    }

    private static String setting(String property, String environment) {
        String value = System.getProperty(property); return value == null || value.isBlank() ? System.getenv(environment) : value;
    }
    private static String requiredSetting(String property, String environment) {
        String value = setting(property, environment);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing required semantic setting: " + property + " / " + environment);
        return value.trim();
    }
    private static int parsePositiveInt(String value, String label) {
        try { int parsed = Integer.parseInt(value.trim()); if (parsed < 1) throw new NumberFormatException("not positive"); return parsed; }
        catch (NumberFormatException exception) { throw new IllegalArgumentException(label + " must be a positive integer", exception); }
    }
    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " must not be blank");
        return value;
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

    public IndexerRegistry indexerRegistry(String providerOverride) {
        IndexerRegistry registry = new IndexerRegistry();
        if (providerOverride == null || providerOverride.isBlank()) { registry.registerAll(indexerDescriptors); return registry; }
        IndexerDescriptor descriptor = indexerDescriptors.stream().filter(candidate -> providerOverride.equals(candidate.id())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown provider override: " + providerOverride));
        registry.register(descriptor); return registry;
    }

    public static final class Builder {
        private final Path home;
        private StorageBackend storageBackend;
        private ProjectRegistry projectRegistry;
        private CodeKnowledgeSnapshotStore snapshotStore;
        private IndexStateStore indexStateStore;
        private ProjectFingerprintSnapshotStore fingerprintStore;
        private SemanticVectorStore semanticVectorStore;
        private RuntimeObservationStore runtimeObservationStore;
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
        private HostedTenantKeyProvider hostedTenantKeyProvider;
        private Clock hostedClock = Clock.systemUTC();

        private Builder(Path home) { this.home = Objects.requireNonNull(home, "home").toAbsolutePath().normalize(); }
        public Builder storageBackend(StorageBackend value) { this.storageBackend = Objects.requireNonNull(value); return this; }
        public Builder projectRegistry(ProjectRegistry value) { this.projectRegistry = Objects.requireNonNull(value); return this; }
        public Builder snapshotStore(CodeKnowledgeSnapshotStore value) { this.snapshotStore = Objects.requireNonNull(value); return this; }
        public Builder indexStateStore(IndexStateStore value) { this.indexStateStore = Objects.requireNonNull(value); return this; }
        public Builder fingerprintStore(ProjectFingerprintSnapshotStore value) { this.fingerprintStore = Objects.requireNonNull(value); return this; }
        public Builder semanticVectorStore(SemanticVectorStore value) { this.semanticVectorStore = Objects.requireNonNull(value); return this; }
        public Builder runtimeObservationStore(RuntimeObservationStore value) { this.runtimeObservationStore = Objects.requireNonNull(value); return this; }
        public Builder embeddingProvider(EmbeddingProvider value) { this.embeddingProvider = Objects.requireNonNull(value); return this; }
        public Builder hostedTenantKeyProvider(HostedTenantKeyProvider value) { this.hostedTenantKeyProvider = Objects.requireNonNull(value); return this; }
        public Builder hostedClock(Clock value) { this.hostedClock = Objects.requireNonNull(value); return this; }
        public Builder discoveryService(ProjectDiscoveryService value) { this.discoveryService = Objects.requireNonNull(value); return this; }
        public Builder fingerprintService(ProjectFingerprintService value) { this.fingerprintService = Objects.requireNonNull(value); return this; }
        public Builder invalidationService(ProjectInvalidationService value) { this.invalidationService = Objects.requireNonNull(value); return this; }
        public Builder incrementalIndexingPlanner(IncrementalIndexingPlanner value) { this.incrementalIndexingPlanner = Objects.requireNonNull(value); return this; }
        public Builder providerRuntimeManager(ProviderRuntimeManager value) { this.providerRuntimeManager = Objects.requireNonNull(value); return this; }
        public Builder indexerDescriptors(List<IndexerDescriptor> value) { this.indexerDescriptors = List.copyOf(Objects.requireNonNull(value)); return this; }
        public Builder snapshotLifecycle(SnapshotStager stager, SnapshotPromoter promoter) { this.snapshotStager = Objects.requireNonNull(stager); this.snapshotPromoter = Objects.requireNonNull(promoter); return this; }
        public Builder gitIntelligence(GitIntelligenceService value) { this.gitIntelligence = Objects.requireNonNull(value); return this; }
        public Builder programGraphProviders(List<ProgramGraphProvider> value) {
            this.programGraphProviders = List.copyOf(Objects.requireNonNull(value));
            if (this.programGraphProviders.isEmpty()) throw new IllegalArgumentException("programGraphProviders must not be empty");
            return this;
        }

        public MinosApplication build() throws IOException {
            StorageBackend selected = storageBackend;
            boolean explicitStore = projectRegistry != null || snapshotStore != null || indexStateStore != null
                    || fingerprintStore != null || semanticVectorStore != null || runtimeObservationStore != null;
            if (selected == null && !explicitStore) selected = StorageBackends.open(StorageBackendConfiguration.resolve(home));
            if (selected == null) selected = new com.minos.storage.LocalStorageBackend(home);

            ProjectRegistry effectiveRegistry = projectRegistry != null ? projectRegistry : selected.projectRegistry();
            CodeKnowledgeSnapshotStore effectiveSnapshots = snapshotStore != null ? snapshotStore : selected.snapshotStore();
            IndexStateStore effectiveIndexState = indexStateStore != null ? indexStateStore : selected.indexStateStore();
            ProjectFingerprintSnapshotStore effectiveFingerprints = fingerprintStore != null ? fingerprintStore : selected.fingerprintStore();
            SemanticVectorStore effectiveSemanticStore = semanticVectorStore != null ? semanticVectorStore : selected.semanticVectorStore();
            RuntimeObservationStore effectiveRuntimeObservations = runtimeObservationStore != null ? runtimeObservationStore : selected.runtimeObservationStore();
            ProjectDiscoveryService effectiveDiscovery = discoveryService != null ? discoveryService : new ProjectDiscoveryService();
            ProjectFingerprintService effectiveFingerprintService = fingerprintService != null ? fingerprintService : new ProjectFingerprintService();
            ProjectInvalidationService effectiveInvalidation = invalidationService != null ? invalidationService : new ProjectInvalidationService();
            IncrementalIndexingPlanner effectivePlanner = incrementalIndexingPlanner != null ? incrementalIndexingPlanner : new IncrementalIndexingPlanner();
            List<IndexerDescriptor> effectiveDescriptors = indexerDescriptors != null ? indexerDescriptors : List.copyOf(ScipIndexerCatalog.qualifiedM24Descriptors());
            ProviderRuntimeManager effectiveProviderRuntime = providerRuntimeManager != null ? providerRuntimeManager
                    : new CompositeProviderRuntimeManager(List.of(new ManagedScipProviderRuntimeManager(home),
                    new ManagedScipPythonRuntimeManager(home), new ManagedPolyglotScipRuntimeManager(home)));
            SnapshotStager effectiveStager = snapshotStager;
            SnapshotPromoter effectivePromoter = snapshotPromoter;
            if ((effectiveStager == null) != (effectivePromoter == null)) throw new IllegalStateException("snapshot stager and promoter must be configured together");
            if (effectiveStager == null) {
                ScipProjectSnapshotLifecycle lifecycle = new ScipProjectSnapshotLifecycle(home, effectiveSnapshots, effectiveDescriptors);
                effectiveStager = lifecycle; effectivePromoter = lifecycle;
            }
            GitIntelligenceService effectiveGit = gitIntelligence != null ? gitIntelligence : new GitIntelligenceService();
            List<ProgramGraphProvider> effectiveProgramGraphProviders = programGraphProviders != null
                    ? programGraphProviders : ProgramGraphService.productionProviders(effectiveFingerprints);
            Optional<HostedControlPlaneService> effectiveHosted = Optional.empty();
            if (hostedTenantKeyProvider != null) {
                FileHostedControlPlaneStore hostedStore = new FileHostedControlPlaneStore(home.resolve("hosted-control-plane"), hostedTenantKeyProvider);
                HmacHostedIdentityProvider hostedIdentities = new HmacHostedIdentityProvider(hostedTenantKeyProvider);
                CodeKnowledgeSnapshotStore exactSnapshots = effectiveSnapshots;
                effectiveHosted = Optional.of(new HostedControlPlaneService(hostedStore, hostedIdentities, hostedTenantKeyProvider,
                        (projectId, snapshotId) -> {
                            var active = exactSnapshots.loadActiveKnowledge(projectId)
                                    .orElseThrow(() -> new IOException("hosted project has no active snapshot: " + projectId));
                            if (!snapshotId.equals(active.snapshotId())) throw new IOException("hosted binding requires the exact active snapshot: " + snapshotId);
                        }, hostedClock));
            }
            return new MinosApplication(home, selected.id(), effectiveRegistry, effectiveSnapshots, effectiveIndexState,
                    effectiveFingerprints, effectiveSemanticStore, effectiveRuntimeObservations, effectiveDiscovery,
                    effectiveFingerprintService, effectiveInvalidation, effectivePlanner, effectiveProviderRuntime,
                    effectiveDescriptors, effectiveStager, effectivePromoter, effectiveGit, effectiveProgramGraphProviders,
                    Optional.ofNullable(embeddingProvider), effectiveHosted);
        }
    }
}
