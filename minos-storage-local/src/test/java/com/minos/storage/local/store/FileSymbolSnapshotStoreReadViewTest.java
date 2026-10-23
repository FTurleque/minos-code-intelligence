package com.minos.storage.local.store;

import com.minos.domain.Relationship;
import com.minos.domain.Symbol;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.IncomingRelationships;
import com.minos.store.SnapshotQueryView;
import com.minos.store.SnapshotReadView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AUD-ARC-07 (ADR 0056) : le store fichier sert et met en cache la vue neutre ; l'ancien contrat partage la même
 * construction et le même index des arêtes entrantes, et la cohérence pendant une promotion concurrente est gardée.
 */
@SuppressWarnings("deprecation")
class FileSymbolSnapshotStoreReadViewTest {

    @Test
    void theReadViewAndTheLegacyViewShareOneBuildAndOneIncomingIndex(@TempDir Path root) throws Exception {
        UUID projectId = UUID.randomUUID();
        CodeKnowledgeSnapshot published = QueryViewWeightFixtures.repository(projectId, "shared", 4,
                List.<String[]>of(QueryViewWeightFixtures.ASCII));
        FileSymbolSnapshotStore store = new FileSymbolSnapshotStore(root);
        store.publish(projectId, "shared", published.symbols(), published.occurrences(), published.relationships());

        SnapshotReadView read = store.loadActiveReadView(projectId).orElseThrow();
        IncomingRelationships byReadView = read.incomingRelationships();
        SnapshotQueryView legacy = store.loadActiveQueryView(projectId).orElseThrow();
        IncomingRelationships byLegacyView = legacy.queryStore().symbolEdgeIndex(projectId.toString());

        assertEquals(1, store.cacheStats().queryViewBuilds());
        assertEquals(1, store.cacheStats().fullSnapshotLoads());
        assertSame(read.reader(), legacy.queryStore());
        assertSame(read.snapshot(), legacy.snapshot());
        assertSame(read.descriptor(), legacy.descriptor());
        assertSame(byReadView, byLegacyView);
        assertEquals(1L, legacy.queryStore().symbolEdgeIndexBuilds());
        assertTrue(byReadView.targetCount() > 0, "the fixture has incoming relationships");
        for (Symbol symbol : read.snapshot().symbols()) {
            assertEquals(ids(byReadView.incoming(symbol.id())), ids(byLegacyView.incoming(symbol.id())));
        }
    }

    @Test
    void twoReadsOfTheReadViewReturnTheCachedInstance(@TempDir Path root) throws Exception {
        UUID projectId = UUID.randomUUID();
        FileSymbolSnapshotStore store = new FileSymbolSnapshotStore(root);
        store.publish(projectId, "snapshot-1", FileSymbolSnapshotStoreTest.symbols(projectId));

        SnapshotReadView first = store.loadActiveReadView(projectId).orElseThrow();
        SnapshotReadView second = store.loadActiveReadView(projectId).orElseThrow();

        assertSame(first, second);
        assertEquals(1, store.cacheStats().queryViewBuilds());
        assertEquals(1, store.cacheStats().hits());
    }

    @Test
    void aPromotionDuringTheBuildServesTheNewSnapshotAndCachesNothingOfTheOldOne(@TempDir Path root)
            throws Exception {
        UUID projectId = UUID.randomUUID();
        AtomicReference<FileSymbolSnapshotStore> promoter = new AtomicReference<>();
        AtomicBoolean switched = new AtomicBoolean();
        FileSymbolSnapshotStore store = new FileSymbolSnapshotStore(
                root,
                FileSymbolSnapshotStore.DEFAULT_MAX_QUERY_CACHE_ENTRIES,
                FileSymbolSnapshotStore.DEFAULT_MAX_QUERY_CACHE_WEIGHT_BYTES,
                2,
                (id, descriptor) -> {
                    if (switched.compareAndSet(false, true)) {
                        promoter.get().publish(id, "snapshot-2",
                                List.of(FileSymbolSnapshotStoreTest.symbols(id).get(1)));
                    }
                });
        promoter.set(new FileSymbolSnapshotStore(root));
        store.publish(projectId, "snapshot-1", List.of(FileSymbolSnapshotStoreTest.symbols(projectId).get(0)));

        SnapshotReadView view = store.loadActiveReadView(projectId).orElseThrow();

        assertTrue(switched.get());
        assertEquals("snapshot-2", view.snapshot().snapshotId());
        assertEquals("snapshot-2", view.descriptor().snapshotId());
        assertEquals(2, store.cacheStats().queryViewBuilds());
        assertEquals(1, store.cacheStats().entries(), "only the view of the active snapshot is cached");
        assertSame(view, store.loadActiveReadView(projectId).orElseThrow());
    }

    private static List<String> ids(List<Relationship> relationships) {
        return relationships.stream().map(Relationship::id).toList();
    }
}
