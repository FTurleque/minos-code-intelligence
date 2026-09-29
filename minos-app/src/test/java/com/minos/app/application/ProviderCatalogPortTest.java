package com.minos.app.application;

import com.minos.adapter.scip.ScipIndexerCatalog;
import com.minos.application.MinosApplication;
import com.minos.application.ProviderPlatformService;
import com.minos.orchestration.IndexerProvider;
import com.minos.orchestration.IndexerProviderCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A2 — ProviderPlatformService atteint le catalogue de providers par un port, lu à la demande. */
class ProviderCatalogPortTest {

    @Test
    void defaultCompositionServesTheQualifiedScipCatalogThroughThePort(@TempDir Path temp) throws Exception {
        try (MinosApplication application = MinosApplication.builder(temp.resolve("home")).build()) {
            List<String> expected = ScipIndexerCatalog.qualifiedM24Providers().stream()
                    .map(provider -> provider.descriptor().id())
                    .toList();
            assertEquals(expected, application.providerCatalog().providers().stream()
                    .map(provider -> provider.descriptor().id())
                    .toList());
            assertEquals(expected.stream().sorted().toList(),
                    ProviderPlatformService.defaults(application).listProviders().stream()
                            .map(ProviderPlatformService.ProviderView::id)
                            .toList());
        }
    }

    @Test
    void theCatalogIsReadWhenThePlatformViewIsBuiltNotWhenTheApplicationIsComposed(@TempDir Path temp)
            throws Exception {
        AtomicInteger reads = new AtomicInteger();
        IndexerProvider only = ScipIndexerCatalog.qualifiedM24Providers().getFirst();
        IndexerProviderCatalog counting = () -> {
            reads.incrementAndGet();
            return List.of(only);
        };
        try (MinosApplication application = MinosApplication.builder(temp.resolve("home"))
                .providerCatalog(counting)
                .build()) {
            assertEquals(0, reads.get());
            ProviderPlatformService platform = ProviderPlatformService.defaults(application);
            assertEquals(1, reads.get());
            assertEquals(List.of(only.descriptor().id()),
                    platform.listProviders().stream().map(ProviderPlatformService.ProviderView::id).toList());
            assertEquals(1, reads.get());
        }
    }
}
