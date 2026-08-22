package com.minos.application;

import com.minos.adapter.scip.ScipIndexerCatalog;
import com.minos.adapter.scip.runtime.ManagedPolyglotScipRuntimeManager;
import com.minos.adapter.scip.runtime.ManagedScipProviderRuntimeManager;
import com.minos.adapter.scip.runtime.ManagedScipPythonRuntimeManager;
import com.minos.adapter.scip.runtime.ScipProjectSnapshotLifecycle;
import com.minos.discovery.ProjectDiscoveryService;
import com.minos.dynamic.RuntimeObservationStore;
import com.minos.git.GitIntelligenceService;
import com.minos.hosted.HmacHostedIdentityProvider;
import com.minos.hosted.HostedControlPlaneService;
import com.minos.incremental.IncrementalIndexingPlanner;
import com.minos.incremental.ProjectFingerprintService;
import com.minos.incremental.ProjectFingerprintSnapshotStore;
import com.minos.incremental.ProjectInvalidationService;
import com.minos.io.PrivateLocalStorage;
import com.minos.orchestration.IndexStateStore;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotPromoter;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotStager;
import com.minos.program.analysis.ProgramGraphProvider;
import com.minos.program.analysis.ProgramGraphService;
import com.minos.registry.ProjectRegistry;
import com.minos.runtime.CompositeProviderRuntimeManager;
import com.minos.runtime.ProviderRuntimeManager;
import com.minos.semantic.SemanticVectorStore;
import com.minos.storage.StorageBackend;
import com.minos.storage.StorageBackendConfiguration;
import com.minos.storage.StorageBackends;
import com.minos.storage.StorageRetentionService;
import com.minos.store.CodeKnowledgeSnapshotStore;
import com.minos.store.FileHostedControlPlaneStore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Resolves builder overrides and production defaults into one immutable application composition. */
final class MinosApplicationAssembler {
    private MinosApplicationAssembler() {
    }

    static MinosApplication build(MinosApplication.Builder builder) throws IOException {
        Path home = builder.home;
        // Builder is a direct public entry point, so it independently enforces private MINOS_HOME
        // even when callers bypass MinosApplication.open(Path).
        PrivateLocalStorage.ensurePrivateDirectory(home);
        StorageBackend selected = builder.storageBackend;
        boolean explicitStore = builder.projectRegistry != null
                || builder.snapshotStore != null
                || builder.indexStateStore != null
                || builder.fingerprintStore != null
                || builder.semanticVectorStore != null
                || builder.runtimeObservationStore != null;
        if (selected == null && !explicitStore) {
            selected = StorageBackends.open(StorageBackendConfiguration.resolve(home));
        }
        if (selected == null) selected = new com.minos.storage.LocalStorageBackend(home);

        try {
            ProjectRegistry effectiveRegistry = builder.projectRegistry != null
                    ? builder.projectRegistry : selected.projectRegistry();
            CodeKnowledgeSnapshotStore effectiveSnapshots = builder.snapshotStore != null
                    ? builder.snapshotStore : selected.snapshotStore();
            IndexStateStore effectiveIndexState = builder.indexStateStore != null
                    ? builder.indexStateStore : selected.indexStateStore();
            ProjectFingerprintSnapshotStore effectiveFingerprints = builder.fingerprintStore != null
                    ? builder.fingerprintStore : selected.fingerprintStore();
            SemanticVectorStore effectiveSemanticStore = builder.semanticVectorStore != null
                    ? builder.semanticVectorStore : selected.semanticVectorStore();
            RuntimeObservationStore effectiveRuntimeObservations = builder.runtimeObservationStore != null
                    ? builder.runtimeObservationStore : selected.runtimeObservationStore();
            boolean retentionStoresOverridden = builder.snapshotStore != null
                    || builder.indexStateStore != null
                    || builder.fingerprintStore != null;
            StorageRetentionService effectiveRetention = builder.retentionService != null
                    ? builder.retentionService
                    : retentionStoresOverridden ? StorageRetentionService.noOp() : selected.retentionService();
            ProjectDiscoveryService effectiveDiscovery = builder.discoveryService != null
                    ? builder.discoveryService : new ProjectDiscoveryService();
            ProjectFingerprintService effectiveFingerprintService = builder.fingerprintService != null
                    ? builder.fingerprintService : new ProjectFingerprintService();
            ProjectInvalidationService effectiveInvalidation = builder.invalidationService != null
                    ? builder.invalidationService : new ProjectInvalidationService();
            IncrementalIndexingPlanner effectivePlanner = builder.incrementalIndexingPlanner != null
                    ? builder.incrementalIndexingPlanner : new IncrementalIndexingPlanner();
            List<IndexerDescriptor> effectiveDescriptors = builder.indexerDescriptors != null
                    ? builder.indexerDescriptors : List.copyOf(ScipIndexerCatalog.qualifiedM24Descriptors());
            ProviderRuntimeManager effectiveProviderRuntime = builder.providerRuntimeManager != null
                    ? builder.providerRuntimeManager
                    : new CompositeProviderRuntimeManager(List.of(
                            new ManagedScipProviderRuntimeManager(home),
                            new ManagedScipPythonRuntimeManager(home),
                            new ManagedPolyglotScipRuntimeManager(home)));
            SnapshotStager effectiveStager = builder.snapshotStager;
            SnapshotPromoter effectivePromoter = builder.snapshotPromoter;
            if ((effectiveStager == null) != (effectivePromoter == null)) {
                throw new IllegalStateException("snapshot stager and promoter must be configured together");
            }
            if (effectiveStager == null) {
                ScipProjectSnapshotLifecycle lifecycle =
                        new ScipProjectSnapshotLifecycle(home, effectiveSnapshots, effectiveDescriptors);
                effectiveStager = lifecycle;
                effectivePromoter = lifecycle;
            }
            GitIntelligenceService effectiveGit = builder.gitIntelligence != null
                    ? builder.gitIntelligence : new GitIntelligenceService();
            List<ProgramGraphProvider> effectiveProgramGraphProviders = builder.programGraphProviders != null
                    ? builder.programGraphProviders : ProgramGraphService.productionProviders(effectiveFingerprints);
            Optional<HostedControlPlaneService> effectiveHosted = hostedControlPlane(
                    builder, home, effectiveSnapshots);

            return new MinosApplication(
                    home,
                    selected,
                    effectiveRegistry,
                    effectiveSnapshots,
                    effectiveIndexState,
                    effectiveFingerprints,
                    effectiveSemanticStore,
                    effectiveRuntimeObservations,
                    effectiveRetention,
                    effectiveDiscovery,
                    effectiveFingerprintService,
                    effectiveInvalidation,
                    effectivePlanner,
                    effectiveProviderRuntime,
                    effectiveDescriptors,
                    effectiveStager,
                    effectivePromoter,
                    effectiveGit,
                    effectiveProgramGraphProviders,
                    Optional.ofNullable(builder.embeddingProvider),
                    effectiveHosted);
        } catch (IOException | RuntimeException exception) {
            closeBackendOnFailure(selected, exception);
            throw exception;
        }
    }

    private static Optional<HostedControlPlaneService> hostedControlPlane(
            MinosApplication.Builder builder,
            Path home,
            CodeKnowledgeSnapshotStore snapshots
    ) throws IOException {
        if (builder.hostedTenantKeyProvider == null) return Optional.empty();
        FileHostedControlPlaneStore hostedStore = new FileHostedControlPlaneStore(
                home.resolve("hosted-control-plane"), builder.hostedTenantKeyProvider);
        HmacHostedIdentityProvider hostedIdentities =
                new HmacHostedIdentityProvider(builder.hostedTenantKeyProvider);
        return Optional.of(new HostedControlPlaneService(
                hostedStore,
                hostedIdentities,
                builder.hostedTenantKeyProvider,
                (projectId, snapshotId) -> {
                    var active = snapshots.loadActiveKnowledge(projectId)
                            .orElseThrow(() -> new IOException(
                                    "hosted project has no active snapshot: " + projectId));
                    if (!snapshotId.equals(active.snapshotId())) {
                        throw new IOException(
                                "hosted binding requires the exact active snapshot: " + snapshotId);
                    }
                },
                builder.hostedClock));
    }

    private static void closeBackendOnFailure(StorageBackend backend, Exception original) {
        try {
            backend.close();
        } catch (Exception closeFailure) {
            original.addSuppressed(closeFailure);
        }
    }
}
