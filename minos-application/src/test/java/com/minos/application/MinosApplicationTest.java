package com.minos.application;

import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotPromoter;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotStager;
import com.minos.runtime.ProviderRuntimeManager;
import com.minos.runtime.ProviderRuntimeStatus;
import com.minos.store.FileSymbolSnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinosApplicationTest {

    @Test
    void opensOneStableCompositionForOneHome(@TempDir Path root) throws Exception {
        Path requestedHome = root.resolve("nested").resolve("..").resolve("minos-home");

        MinosApplication application = MinosApplication.open(requestedHome);

        assertEquals(requestedHome.toAbsolutePath().normalize(), application.home());
        assertSame(application.projectRegistry(), application.projectRegistry());
        assertSame(application.snapshotStore(), application.snapshotStore());
        assertSame(application.indexStateStore(), application.indexStateStore());
        assertSame(application.fingerprintStore(), application.fingerprintStore());
        assertSame(application.discoveryService(), application.discoveryService());
        assertSame(application.architectureQuery(), application.architectureQuery());
        assertSame(application.impactQuery(), application.impactQuery());
        assertSame(application.workspaceIntelligence(), application.workspaceIntelligence());
        assertSame(application.providerRuntimeManager(), application.providerRuntimeManager());
        assertSame(application.snapshotStager(), application.snapshotStager());
        assertSame(application.snapshotPromoter(), application.snapshotPromoter());
        assertSame(application.gitIntelligence(), application.gitIntelligence());
        assertEquals(2, application.indexerDescriptors().size());
        assertTrue(application.indexerDescriptors().stream().anyMatch(value -> "scip-java".equals(value.id())));
        assertTrue(application.indexerDescriptors().stream().anyMatch(value -> "scip-typescript".equals(value.id())));
    }

    @Test
    void builderAcceptsInjectedRuntimeAndSnapshotPorts(@TempDir Path root) throws Exception {
        FileSymbolSnapshotStore snapshots = new FileSymbolSnapshotStore(root.resolve("custom-snapshots"));
        ProviderRuntimeManager runtime = new ProviderRuntimeManager() {
            @Override
            public List<ProviderRuntimeStatus> list() {
                return List.of();
            }

            @Override
            public ProviderRuntimeStatus inspect(String providerId) {
                throw new UnsupportedOperationException("test runtime");
            }

            @Override
            public ProviderRuntimeStatus install(String providerId) {
                throw new UnsupportedOperationException("test runtime");
            }

            @Override
            public IndexerExecutor executor(String providerId) {
                throw new UnsupportedOperationException("test runtime");
            }
        };
        SnapshotStager stager = request -> "test-staged";
        SnapshotPromoter promoter = (projectId, runId, stagedSnapshotId) -> { };

        MinosApplication application = MinosApplication.builder(root.resolve("home"))
                .snapshotStore(snapshots)
                .providerRuntimeManager(runtime)
                .snapshotLifecycle(stager, promoter)
                .build();

        assertSame(snapshots, application.snapshotStore());
        assertSame(runtime, application.providerRuntimeManager());
        assertSame(stager, application.snapshotStager());
        assertSame(promoter, application.snapshotPromoter());
    }
}
