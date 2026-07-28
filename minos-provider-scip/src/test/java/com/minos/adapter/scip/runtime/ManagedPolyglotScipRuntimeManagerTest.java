package com.minos.adapter.scip.runtime;

import com.minos.adapter.scip.ScipIndexerCatalog;
import com.minos.runtime.ProviderRuntimeStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedPolyglotScipRuntimeManagerTest {

    @Test
    void listsEveryM24RuntimeWithoutMakingItRequiredByDefault(@TempDir Path home) {
        ManagedPolyglotScipRuntimeManager manager = new ManagedPolyglotScipRuntimeManager(home);
        Map<String, ProviderRuntimeStatus> statuses = manager.list().stream()
                .collect(Collectors.toMap(ProviderRuntimeStatus::providerId, Function.identity()));

        assertEquals(4, statuses.size());
        assertEquals(ScipIndexerCatalog.SCIP_CLANG_VERSION,
                statuses.get(ScipIndexerCatalog.SCIP_CLANG_ID).version());
        assertEquals(ScipIndexerCatalog.SCIP_DOTNET_VERSION,
                statuses.get(ScipIndexerCatalog.SCIP_DOTNET_ID).version());
        assertEquals(ScipIndexerCatalog.SCIP_GO_VERSION,
                statuses.get(ScipIndexerCatalog.SCIP_GO_ID).version());
        assertEquals(ScipIndexerCatalog.RUST_ANALYZER_SCIP_VERSION,
                statuses.get(ScipIndexerCatalog.RUST_ANALYZER_SCIP_ID).version());
        assertTrue(statuses.values().stream().noneMatch(ProviderRuntimeStatus::requiredByDefault));
        assertFalse(Files.exists(home.resolve("tools")), "readiness inspection must not install or mutate toolchains");
    }

    @Test
    void refusesImplicitCompilerToolchainInstallation(@TempDir Path home) {
        ManagedPolyglotScipRuntimeManager manager = new ManagedPolyglotScipRuntimeManager(home);
        IllegalStateException clang = assertThrows(IllegalStateException.class,
                () -> manager.install(ScipIndexerCatalog.SCIP_CLANG_ID));
        assertTrue(clang.getMessage().contains("operator-managed"));

        IllegalStateException rust = assertThrows(IllegalStateException.class,
                () -> manager.install(ScipIndexerCatalog.RUST_ANALYZER_SCIP_ID));
        assertTrue(rust.getMessage().contains("rustup"));
    }

    @Test
    void rejectsUnknownOrBlankProviderIds(@TempDir Path home) {
        ManagedPolyglotScipRuntimeManager manager = new ManagedPolyglotScipRuntimeManager(home);
        assertThrows(IllegalArgumentException.class, () -> manager.inspect(""));
        assertThrows(IllegalArgumentException.class, () -> manager.inspect("not-a-provider"));
        assertThrows(IllegalArgumentException.class, () -> manager.install("not-a-provider"));
    }

    @Test
    void windowsReportsClangAsBlockedInsteadOfPretendingCrossPlatformSupport(@TempDir Path home) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return;
        }
        ManagedPolyglotScipRuntimeManager manager = new ManagedPolyglotScipRuntimeManager(home);
        ProviderRuntimeStatus status = manager.inspect(ScipIndexerCatalog.SCIP_CLANG_ID);
        assertEquals(ProviderRuntimeStatus.State.BLOCKED, status.state());
        assertTrue(status.executable().isEmpty());
        assertTrue(status.diagnostics().stream().anyMatch(value -> value.contains("Windows")));
    }
}
