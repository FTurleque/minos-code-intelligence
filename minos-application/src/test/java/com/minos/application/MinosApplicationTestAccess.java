package com.minos.application;

import com.minos.discovery.ProjectDiscoveryService;
import com.minos.dynamic.RuntimeObservationStore;
import com.minos.incremental.ProjectFingerprintService;
import com.minos.incremental.ProjectFingerprintSnapshotStore;
import com.minos.orchestration.IndexStateStore;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotPromoter;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotStager;
import com.minos.registry.ProjectRegistry;
import com.minos.runtime.ProviderRuntimeManager;
import com.minos.storage.StorageRetentionService;
import com.minos.store.CodeKnowledgeSnapshotStore;

import java.util.List;

/**
 * Test access to the ports of an open {@link MinosApplication} (AUD-ARC-02, ADR 0087 section 2).
 *
 * <p>The ports and the composition root of the facade are package-private: the classes of this package read them, the
 * surfaces go through the services and use cases of the facade. A test of another module that prepares or inspects the
 * stores of an application opened by {@link MinosApplication#open} reads them here. This class lives in the test-jar of
 * minos-application, declared in test scope only (check-module-boundaries.py), and in no delivered jar; each method
 * relays the accessor of the same name without copy.</p>
 */
public final class MinosApplicationTestAccess {

    private MinosApplicationTestAccess() {
    }

    public static MinosApplicationComposer compositionRoot(MinosApplication application) {
        return application.compositionRoot();
    }

    public static ProjectRegistry projectRegistry(MinosApplication application) {
        return application.projectRegistry();
    }

    public static CodeKnowledgeSnapshotStore snapshotStore(MinosApplication application) {
        return application.snapshotStore();
    }

    public static IndexStateStore indexStateStore(MinosApplication application) {
        return application.indexStateStore();
    }

    public static ProjectFingerprintSnapshotStore fingerprintStore(MinosApplication application) {
        return application.fingerprintStore();
    }

    public static RuntimeObservationStore runtimeObservationStore(MinosApplication application) {
        return application.runtimeObservationStore();
    }

    public static StorageRetentionService retentionService(MinosApplication application) {
        return application.retentionService();
    }

    public static ProjectDiscoveryService discoveryService(MinosApplication application) {
        return application.discoveryService();
    }

    public static ProjectFingerprintService fingerprintService(MinosApplication application) {
        return application.fingerprintService();
    }

    public static ProviderRuntimeManager providerRuntimeManager(MinosApplication application) {
        return application.providerRuntimeManager();
    }

    public static List<IndexerDescriptor> indexerDescriptors(MinosApplication application) {
        return application.indexerDescriptors();
    }

    public static SnapshotStager snapshotStager(MinosApplication application) {
        return application.snapshotStager();
    }

    public static SnapshotPromoter snapshotPromoter(MinosApplication application) {
        return application.snapshotPromoter();
    }
}
