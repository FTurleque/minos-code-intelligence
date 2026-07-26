package com.minos.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class FileSymbolSnapshotStoreCacheTest {

    @Test
    void secondReadReusesTheSameIndexedView(@TempDir Path root) throws Exception {
        UUID projectId = UUID.randomUUID();
        FileSymbolSnapshotStore store = new FileSymbolSnapshotStore(root);
        store.publish(projectId, "snapshot-1", FileSymbolSnapshotStoreTest.symbols(projectId));

        SnapshotQueryView first = store.loadActiveQueryView(projectId).orElseThrow();
        SnapshotQueryView second = store.loadActiveQueryView(projectId).orElseThrow();

        assertSame(first, second);
        assertEquals(1, store.cacheStats().fullSnapshotLoads());
        assertEquals(1, store.cacheStats().queryViewBuilds());
        assertEquals(1, store.cacheStats().hits());
    }

    @Test
    void externalPromotionBecomesVisibleWithoutExplicitInvalidation(@TempDir Path root) throws Exception {
        UUID projectId = UUID.randomUUID();
        FileSymbolSnapshotStore longLived = new FileSymbolSnapshotStore(root);
        longLived.publish(projectId, "snapshot-1", List.of(FileSymbolSnapshotStoreTest.symbols(projectId).get(0)));
        SnapshotQueryView first = longLived.loadActiveQueryView(projectId).orElseThrow();

        FileSymbolSnapshotStore promoter = new FileSymbolSnapshotStore(root);
        promoter.publish(projectId, "snapshot-2", List.of(FileSymbolSnapshotStoreTest.symbols(projectId).get(1)));

        SnapshotQueryView second = longLived.loadActiveQueryView(projectId).orElseThrow();
        assertNotSame(first, second);
        assertEquals("snapshot-2", second.snapshot().snapshotId());
        assertEquals(List.of("method-string"), second.snapshot().symbols().stream().map(symbol -> symbol.id()).toList());
        assertEquals(2, longLived.cacheStats().fullSnapshotLoads());
    }

    @Test
    void republishingTheSameLogicalSnapshotIdCannotReturnStaleContent(@TempDir Path root) throws Exception {
        UUID projectId = UUID.randomUUID();
        FileSymbolSnapshotStore longLived = new FileSymbolSnapshotStore(root);
        longLived.publish(projectId, "stable", List.of(FileSymbolSnapshotStoreTest.symbols(projectId).get(0)));
        SnapshotQueryView first = longLived.loadActiveQueryView(projectId).orElseThrow();

        new FileSymbolSnapshotStore(root).publish(
                projectId,
                "stable",
                List.of(FileSymbolSnapshotStoreTest.symbols(projectId).get(1))
        );

        SnapshotQueryView second = longLived.loadActiveQueryView(projectId).orElseThrow();
        assertNotSame(first, second);
        assertEquals("stable", second.snapshot().snapshotId());
        assertEquals(List.of("method-string"), second.snapshot().symbols().stream().map(symbol -> symbol.id()).toList());
    }

    @Test
    void concurrentCacheMissBuildsOnlyOneView(@TempDir Path root) throws Exception {
        UUID projectId = UUID.randomUUID();
        FileSymbolSnapshotStore store = new FileSymbolSnapshotStore(root);
        store.publish(projectId, "snapshot-1", FileSymbolSnapshotStoreTest.symbols(projectId));

        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Callable<SnapshotQueryView>> tasks = java.util.stream.IntStream.range(0, 16)
                    .mapToObj(ignored -> (Callable<SnapshotQueryView>) () ->
                            store.loadActiveQueryView(projectId).orElseThrow())
                    .toList();
            List<SnapshotQueryView> views = executor.invokeAll(tasks).stream()
                    .map(future -> {
                        try {
                            return future.get();
                        } catch (Exception exception) {
                            throw new RuntimeException(exception);
                        }
                    })
                    .toList();

            SnapshotQueryView expected = views.getFirst();
            views.forEach(view -> assertSame(expected, view));
        }

        assertEquals(1, store.cacheStats().fullSnapshotLoads());
        assertEquals(1, store.cacheStats().queryViewBuilds());
        assertEquals(15, store.cacheStats().hits());
    }
}
