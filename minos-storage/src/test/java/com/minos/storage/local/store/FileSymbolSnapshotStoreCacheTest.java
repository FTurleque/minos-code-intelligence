package com.minos.storage.local.store;

import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.SnapshotQueryView;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void aViewWithinTheMeasuredAmplificationOfItsFileIsCached(@TempDir Path root) throws Exception {
        UUID projectId = UUID.randomUUID();
        CodeKnowledgeSnapshot snapshot = QueryViewWeightFixtures.repository(projectId, "admitted", 20,
                List.<String[]>of(QueryViewWeightFixtures.ASCII));
        long persisted = new SnapshotCodecV3().encodedSize(snapshot);
        // Three times the persisted size: above the measured amplification, below the former factor of 8.
        FileSymbolSnapshotStore store = new FileSymbolSnapshotStore(root, 4, 3L * persisted);
        store.publish(projectId, "admitted", snapshot.symbols(), snapshot.occurrences(), snapshot.relationships());

        SnapshotQueryView first = store.loadActiveQueryView(projectId).orElseThrow();
        SnapshotQueryView second = store.loadActiveQueryView(projectId).orElseThrow();

        assertEquals(1, store.cacheStats().fullSnapshotLoads());
        assertEquals(1, store.cacheStats().entries());
        assertTrue(first == second, "the second read returns the cached view");
    }

    @Test
    void aViewHeavierThanTheCacheCeilingIsReloadedOnEveryRead(@TempDir Path root) throws Exception {
        UUID projectId = UUID.randomUUID();
        CodeKnowledgeSnapshot snapshot = QueryViewWeightFixtures.repository(projectId, "refused", 20,
                List.<String[]>of(QueryViewWeightFixtures.ASCII));
        long persisted = new SnapshotCodecV3().encodedSize(snapshot);
        FileSymbolSnapshotStore store = new FileSymbolSnapshotStore(root, 4, persisted);
        store.publish(projectId, "refused", snapshot.symbols(), snapshot.occurrences(), snapshot.relationships());

        SnapshotQueryView first = store.loadActiveQueryView(projectId).orElseThrow();
        SnapshotQueryView second = store.loadActiveQueryView(projectId).orElseThrow();

        assertEquals(2, store.cacheStats().fullSnapshotLoads());
        assertEquals(0, store.cacheStats().entries());
        assertTrue(first != second, "a view that is not admitted is rebuilt");
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
