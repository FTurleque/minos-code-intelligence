package com.minos.application;

import com.minos.adapter.scip.ScipIndexerCatalog;
import com.minos.orchestration.IndexerProvider;
import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.runtime.ProviderRuntimeManager;
import com.minos.runtime.ProviderRuntimeStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ProviderPlatformDiagnosticRedactionTest {

    @Test
    void sharedProviderViewRedactsRuntimeDiagnosticsBeforeApiOrMcpCanObserveThem() {
        IndexerProvider provider = ScipIndexerCatalog.qualifiedM24Providers().getFirst();
        ProviderRuntimeStatus status = new ProviderRuntimeStatus(
                provider.descriptor().id(),
                provider.descriptor().version(),
                ProviderRuntimeStatus.State.BLOCKED,
                Optional.empty(),
                List.of("provider failed at /home/private-user/.minos/providers/token"),
                true);
        ProviderPlatformService service = new ProviderPlatformService(
                List.of(provider), new FixedRuntimeManager(status));

        ProviderPlatformService.ProviderView listed = service.listProviders().getFirst();
        ProviderPlatformService.ProviderView inspected = service.inspect(provider.descriptor().id());

        assertEquals(List.of("internal diagnostic redacted"), listed.runtimeDiagnostics());
        assertEquals(listed.runtimeDiagnostics(), inspected.runtimeDiagnostics());
        assertFalse(listed.runtimeDiagnostics().toString().contains("private-user"));
    }

    private record FixedRuntimeManager(ProviderRuntimeStatus status) implements ProviderRuntimeManager {
        @Override public List<ProviderRuntimeStatus> list() { return List.of(status); }
        @Override public ProviderRuntimeStatus inspect(String providerId) { return status; }
        @Override public ProviderRuntimeStatus install(String providerId) { return status; }
        @Override public IndexerExecutor executor(String providerId) { return null; }
    }
}
