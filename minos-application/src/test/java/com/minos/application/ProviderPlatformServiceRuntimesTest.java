package com.minos.application;

import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.runtime.ProviderRuntimeManager;
import com.minos.runtime.ProviderRuntimeStatus;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AUD-ARC-01 (D7 = A) : la liste et l'installation des runtimes de providers passent par
 * {@link ProviderPlatformService}, en résumés applicatifs ; la CLI ne nomme plus le gestionnaire de runtimes.
 */
class ProviderPlatformServiceRuntimesTest {

    private static final Path EXECUTABLE = Path.of("tools", "scip-java").toAbsolutePath();

    @Test
    void runtimesListsEveryManagedRuntimeAsASummary() {
        RecordingRuntimes runtimes = new RecordingRuntimes();
        ProviderPlatformService service = new ProviderPlatformService(List.of(), runtimes);

        List<ProviderRuntimeSummary> summaries = service.runtimes();

        assertEquals(List.of("list"), runtimes.calls);
        assertEquals(2, summaries.size());
        ProviderRuntimeSummary ready = summaries.getFirst();
        assertEquals("scip-java", ready.providerId());
        assertEquals("0.10.0", ready.version());
        assertEquals("READY", ready.state());
        assertEquals(Optional.of(EXECUTABLE.normalize().toString()), ready.executable());
        assertEquals(List.of(), ready.diagnostics());
        assertTrue(ready.requiredByDefault());
        ProviderRuntimeSummary missing = summaries.get(1);
        assertEquals("NOT_INSTALLED", missing.state());
        assertEquals(Optional.empty(), missing.executable());
        assertEquals(List.of("run `minos tools install scip-python`"), missing.diagnostics());
        assertFalse(missing.requiredByDefault());
    }

    @Test
    void installRuntimeInstallsThroughTheManagerAndReturnsItsSummary() throws Exception {
        RecordingRuntimes runtimes = new RecordingRuntimes();
        ProviderPlatformService service = new ProviderPlatformService(List.of(), runtimes);

        ProviderRuntimeSummary installed = service.installRuntime("scip-python");

        assertEquals(List.of("install:scip-python"), runtimes.calls);
        assertEquals("scip-python", installed.providerId());
        assertEquals("READY", installed.state());
    }

    @Test
    void installRuntimePropagatesTheManagerFailureUnchanged() {
        RecordingRuntimes runtimes = new RecordingRuntimes();
        ProviderPlatformService service = new ProviderPlatformService(List.of(), runtimes);

        assertEquals("unknown provider: nope",
                assertThrows(IllegalArgumentException.class, () -> service.installRuntime("nope")).getMessage());
        assertEquals(List.of("install:nope"), runtimes.calls);
    }

    @Test
    void theSummaryValidatesItsFieldsAndCopiesItsDiagnostics() {
        List<String> diagnostics = new ArrayList<>(List.of("first"));
        ProviderRuntimeSummary summary = new ProviderRuntimeSummary(
                "scip-java", "1", "READY", Optional.empty(), diagnostics, true);
        diagnostics.add("second");

        assertEquals(List.of("first"), summary.diagnostics());
        assertThrows(UnsupportedOperationException.class, () -> summary.diagnostics().add("third"));
        assertThrows(IllegalArgumentException.class,
                () -> new ProviderRuntimeSummary(" ", "1", "READY", Optional.empty(), List.of(), true));
        assertThrows(IllegalArgumentException.class,
                () -> new ProviderRuntimeSummary("scip-java", "", "READY", Optional.empty(), List.of(), true));
        assertThrows(IllegalArgumentException.class,
                () -> new ProviderRuntimeSummary("scip-java", "1", " ", Optional.empty(), List.of(), true));
        assertThrows(NullPointerException.class,
                () -> new ProviderRuntimeSummary("scip-java", "1", "READY", null, List.of(), true));
        assertThrows(NullPointerException.class,
                () -> new ProviderRuntimeSummary("scip-java", "1", "READY", Optional.empty(), null, true));
    }

    /** A runtime manager that records what it is asked and never starts anything. */
    private static final class RecordingRuntimes implements ProviderRuntimeManager {
        final List<String> calls = new ArrayList<>();

        @Override
        public List<ProviderRuntimeStatus> list() {
            calls.add("list");
            return List.of(
                    new ProviderRuntimeStatus("scip-java", "0.10.0", ProviderRuntimeStatus.State.READY,
                            Optional.of(EXECUTABLE), List.of(), true),
                    new ProviderRuntimeStatus("scip-python", "0.6.0", ProviderRuntimeStatus.State.NOT_INSTALLED,
                            Optional.empty(), List.of("run `minos tools install scip-python`"), false));
        }

        @Override
        public ProviderRuntimeStatus inspect(String providerId) {
            throw new AssertionError("the runtime summaries never inspect one runtime");
        }

        @Override
        public ProviderRuntimeStatus install(String providerId) {
            calls.add("install:" + providerId);
            if ("nope".equals(providerId)) throw new IllegalArgumentException("unknown provider: nope");
            return new ProviderRuntimeStatus(providerId, "0.6.0", ProviderRuntimeStatus.State.READY,
                    Optional.of(EXECUTABLE), List.of(), false);
        }

        @Override
        public IndexerExecutor executor(String providerId) {
            throw new AssertionError("the runtime summaries never execute a provider");
        }
    }
}
