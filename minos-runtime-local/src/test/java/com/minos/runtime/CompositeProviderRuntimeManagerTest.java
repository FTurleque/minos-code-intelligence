package com.minos.runtime;

import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompositeProviderRuntimeManagerTest {

    @Test
    void routesRuntimeOperationsWithoutCentralProviderBranches() throws Exception {
        FakeManager alpha = new FakeManager("alpha");
        FakeManager beta = new FakeManager("beta");
        CompositeProviderRuntimeManager manager = new CompositeProviderRuntimeManager(List.of(alpha, beta));

        assertEquals(List.of("alpha", "beta"), manager.list().stream().map(ProviderRuntimeStatus::providerId).toList());
        assertEquals("beta", manager.inspect("beta").providerId());
        assertEquals("alpha", manager.install("alpha").providerId());
        assertEquals("beta", manager.executor("beta").indexerId());
    }

    @Test
    void rejectsDuplicateProviderExtensions() {
        assertThrows(IllegalArgumentException.class,
                () -> new CompositeProviderRuntimeManager(List.of(new FakeManager("alpha"), new FakeManager("alpha"))));
    }

    private static final class FakeManager implements ProviderRuntimeManager {
        private final String id;
        private FakeManager(String id) { this.id = id; }
        @Override public List<ProviderRuntimeStatus> list() { return List.of(inspect(id)); }
        @Override public ProviderRuntimeStatus inspect(String providerId) {
            if (!id.equals(providerId)) throw new IllegalArgumentException("unknown");
            return new ProviderRuntimeStatus(id, "1.0", ProviderRuntimeStatus.State.READY,
                    Optional.of(Path.of(".").toAbsolutePath()), List.of());
        }
        @Override public ProviderRuntimeStatus install(String providerId) { return inspect(providerId); }
        @Override public IndexerExecutor executor(String providerId) {
            inspect(providerId);
            return new IndexerExecutor() {
                @Override public String indexerId() { return id; }
                @Override public com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact execute(
                        com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest request) {
                    throw new UnsupportedOperationException("not executed in routing test");
                }
            };
        }
    }
}
