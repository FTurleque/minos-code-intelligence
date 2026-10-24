package com.minos.application;

import com.minos.discovery.ProjectDiscoveryService;
import com.minos.dynamic.RuntimeObservationStore;
import com.minos.git.GitIntelligence;
import com.minos.hosted.HmacHostedIdentityProvider;
import com.minos.hosted.HostedControlPlaneService;
import com.minos.hosted.HostedControlPlaneStore;
import com.minos.incremental.ProjectFingerprintService;
import com.minos.incremental.ProjectFingerprintSnapshotStore;
import com.minos.io.PrivateLocalStorage;
import com.minos.orchestration.IncrementalIndexingPlanner;
import com.minos.orchestration.IndexStateStore;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexerProviderCatalog;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotPromoter;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotStager;
import com.minos.orchestration.ProjectInvalidationService;
import com.minos.orchestration.ScipArtifactImporter;
import com.minos.program.analysis.ProgramGraphProvider;
import com.minos.registry.ProjectRegistry;
import com.minos.runtime.ProviderRuntimeManager;
import com.minos.semantic.SemanticVectorStore;
import com.minos.storage.StorageBackend;
import com.minos.storage.StorageBackendConfiguration;
import com.minos.storage.StorageRetentionService;
import com.minos.store.CodeKnowledgeSnapshotStore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Resolves builder overrides and production defaults into one immutable application composition. */
final class MinosApplicationAssembler {
    private MinosApplicationAssembler() {
    }

    static MinosApplication build(MinosApplication.Builder builder) throws IOException {
        // ADR 0042: default adapters supplied by the composition root (minos-bootstrap), resolved before
        // any side effect; each default is still created at the same point as before.
        MinosApplicationComposer composer = builder.resolvedComposer();
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
            selected = composer.openStorageBackend(StorageBackendConfiguration.resolve(home));
        }
        if (selected == null) selected = composer.openLocalStorageBackend(home);

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
                    ? builder.indexerDescriptors : List.copyOf(composer.qualifiedIndexerDescriptors());
            // The catalog is read only on demand (ProviderPlatformService.defaults).
            IndexerProviderCatalog effectiveProviderCatalog = builder.providerCatalog != null
                    ? builder.providerCatalog : composer.providerCatalog();
            // A new importer for each import (see the composer).
            ScipArtifactImporter effectiveScipImporter = builder.scipArtifactImporter != null
                    ? builder.scipArtifactImporter : composer.scipArtifactImporter();
            ProviderRuntimeManager effectiveProviderRuntime = builder.providerRuntimeManager != null
                    ? builder.providerRuntimeManager : composer.providerRuntimeManager(home);
            SnapshotStager effectiveStager = builder.snapshotStager;
            SnapshotPromoter effectivePromoter = builder.snapshotPromoter;
            if ((effectiveStager == null) != (effectivePromoter == null)) {
                throw new IllegalStateException("snapshot stager and promoter must be configured together");
            }
            if (effectiveStager == null) {
                MinosApplicationComposer.SnapshotLifecycle lifecycle =
                        composer.snapshotLifecycle(home, effectiveSnapshots, effectiveDescriptors);
                effectiveStager = lifecycle.stager();
                effectivePromoter = lifecycle.promoter();
            }
            GitIntelligence effectiveGit = builder.gitIntelligence != null
                    ? builder.gitIntelligence : composer.gitIntelligence();
            List<ProgramGraphProvider> effectiveProgramGraphProviders = builder.programGraphProviders != null
                    ? builder.programGraphProviders
                    : MinosApplication.productionProgramGraphProviders(effectiveFingerprints);
            Optional<HostedControlPlaneService> effectiveHosted = hostedControlPlane(
                    builder, composer, home, effectiveSnapshots);

            return new MinosApplication(
                    home,
                    selected,
                    new MinosApplication.Stores(
                            effectiveRegistry,
                            effectiveSnapshots,
                            effectiveIndexState,
                            effectiveFingerprints,
                            effectiveSemanticStore,
                            effectiveRuntimeObservations,
                            effectiveRetention),
                    new MinosApplication.Indexing(
                            effectiveDiscovery,
                            effectiveFingerprintService,
                            effectiveInvalidation,
                            effectivePlanner,
                            effectiveProviderRuntime,
                            effectiveDescriptors,
                            effectiveProviderCatalog,
                            effectiveScipImporter,
                            effectiveStager,
                            effectivePromoter),
                    effectiveGit,
                    effectiveProgramGraphProviders,
                    Optional.ofNullable(builder.embeddingProvider),
                    effectiveHosted,
                    composer);
        } catch (IOException | RuntimeException exception) {
            closeBackendOnFailure(selected, exception);
            throw exception;
        }
    }

    private static Optional<HostedControlPlaneService> hostedControlPlane(
            MinosApplication.Builder builder,
            MinosApplicationComposer composer,
            Path home,
            CodeKnowledgeSnapshotStore snapshots
    ) throws IOException {
        if (builder.hostedTenantKeyProvider == null) return Optional.empty();
        HostedControlPlaneStore hostedStore = composer.hostedControlPlaneStore(
                home.resolve("hosted-control-plane"), builder.hostedTenantKeyProvider);
        HmacHostedIdentityProvider hostedIdentities =
                new HmacHostedIdentityProvider(builder.hostedTenantKeyProvider);
        return Optional.of(new HostedControlPlaneService(
                hostedStore,
                hostedIdentities,
                builder.hostedTenantKeyProvider,
                (projectId, snapshotId) -> requireExactActiveSnapshot(snapshots, projectId, snapshotId),
                builder.hostedClock));
    }

    /**
     * A hosted binding names the exact active snapshot. Only its identity is compared: the snapshot is not loaded
     * here, and the queries that follow load and verify it.
     */
    static void requireExactActiveSnapshot(CodeKnowledgeSnapshotStore snapshots, UUID projectId, String snapshotId)
            throws IOException {
        String active = snapshots.activeSnapshotId(projectId)
                .orElseThrow(() -> new IOException("hosted project has no active snapshot: " + projectId));
        if (!snapshotId.equals(active)) {
            throw new IOException("hosted binding requires the exact active snapshot: " + snapshotId);
        }
    }

    private static void closeBackendOnFailure(StorageBackend backend, Exception original) {
        try {
            backend.close();
        } catch (Exception closeFailure) {
            original.addSuppressed(closeFailure);
        }
    }
}
