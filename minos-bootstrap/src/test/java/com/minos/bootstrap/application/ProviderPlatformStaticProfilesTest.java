package com.minos.bootstrap.application;

import com.minos.application.MinosApplication;
import com.minos.application.ProviderPlatformService;
import com.minos.application.ProviderPlatformService.ProviderView;
import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.runtime.ProviderRuntimeManager;
import com.minos.runtime.ProviderRuntimeStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MINOS-AUD-C02 : les profils statiques des providers (surface en lecture seule) n'interrogent jamais les runtimes,
 * déclarent leur état d'exécution « non inspecté » et ne diffèrent des profils inspectés que par cet état ;
 * l'inspection explicite ({@code providers}, {@code doctor}, API Java) inspecte toujours.
 */
class ProviderPlatformStaticProfilesTest {

    @TempDir
    Path home;

    @Test
    void staticProfilesNeverAskTheRuntimesAndOnlyTheRuntimeStateDiffersFromInspectedProfiles() throws Exception {
        CountingRuntimes runtimes = new CountingRuntimes();
        try (MinosApplication application = MinosApplication.builder(home).providerRuntimeManager(runtimes).build()) {
            ProviderPlatformService service = ProviderPlatformService.defaults(application);

            List<ProviderView> staticProfiles = service.listStaticProfiles();

            assertEquals(0, runtimes.calls.get(), "static profiles must not list, inspect or install any runtime");
            assertFalse(staticProfiles.isEmpty());
            for (ProviderView profile : staticProfiles) {
                assertEquals(ProviderPlatformService.RUNTIME_NOT_INSPECTED, profile.runtimeState(), profile.id());
                assertTrue(profile.runtimeDiagnostics().getFirst().contains("minos providers"), profile.id());
            }

            List<ProviderView> inspected = service.listProviders();

            assertTrue(runtimes.calls.get() > 0, "the explicit inspection must still ask the runtimes");
            assertEquals(staticProfiles.size(), inspected.size());
            for (int index = 0; index < inspected.size(); index++) {
                ProviderView expected = inspected.get(index);
                ProviderView actual = staticProfiles.get(index);
                assertEquals(expected.id(), actual.id());
                assertEquals(expected.capabilities(), actual.capabilities());
                assertEquals(expected.limitations(), actual.limitations());
                assertEquals(expected.languages(), actual.languages());
                assertEquals(expected.conformanceScorePercent(), actual.conformanceScorePercent());
                assertFalse(expected.runtimeState().equals(actual.runtimeState()), expected.id());
            }
        }
    }

    private static final class CountingRuntimes implements ProviderRuntimeManager {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public List<ProviderRuntimeStatus> list() {
            calls.incrementAndGet();
            return List.of();
        }

        @Override
        public ProviderRuntimeStatus inspect(String providerId) {
            calls.incrementAndGet();
            return new ProviderRuntimeStatus(providerId, "1", ProviderRuntimeStatus.State.NOT_INSTALLED,
                    Optional.empty(), List.of("counted"));
        }

        @Override
        public ProviderRuntimeStatus install(String providerId) {
            calls.incrementAndGet();
            return inspect(providerId);
        }

        @Override
        public IndexerExecutor executor(String providerId) {
            calls.incrementAndGet();
            throw new UnsupportedOperationException("not used");
        }
    }
}
