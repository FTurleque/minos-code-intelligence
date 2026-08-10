package com.minos.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSymbolSnapshotStoreRetryTest {

    @Test
    void transparentlyRetriesOneActivePointerRace(@TempDir Path root) throws Exception {
        UUID projectId = UUID.randomUUID();
        AtomicReference<FileSymbolSnapshotStore> storeRef = new AtomicReference<>();
        AtomicBoolean switched = new AtomicBoolean();
        FileSymbolSnapshotStore store = new FileSymbolSnapshotStore(
                root,
                FileSymbolSnapshotStore.DEFAULT_MAX_QUERY_CACHE_ENTRIES,
                FileSymbolSnapshotStore.DEFAULT_MAX_QUERY_CACHE_WEIGHT_BYTES,
                2,
                (id, descriptor) -> {
                    if (switched.compareAndSet(false, true)) {
                        storeRef.get().publish(
                                id,
                                "snapshot-2",
                                List.of(FileSymbolSnapshotStoreTest.symbols(id).get(1)));
                    }
                });
        storeRef.set(store);
        store.publish(projectId, "snapshot-1", List.of(FileSymbolSnapshotStoreTest.symbols(projectId).get(0)));

        SnapshotQueryView view = store.loadActiveQueryView(projectId).orElseThrow();

        assertEquals("snapshot-2", view.snapshot().snapshotId());
        assertEquals(2, store.cacheStats().queryViewBuilds());
        assertTrue(switched.get());
    }

    @Test
    void failsAfterBoundedRetriesWhenActivePointerKeepsChanging(@TempDir Path root) throws Exception {
        UUID projectId = UUID.randomUUID();
        AtomicReference<FileSymbolSnapshotStore> storeRef = new AtomicReference<>();
        AtomicInteger changes = new AtomicInteger();
        FileSymbolSnapshotStore store = new FileSymbolSnapshotStore(
                root,
                FileSymbolSnapshotStore.DEFAULT_MAX_QUERY_CACHE_ENTRIES,
                FileSymbolSnapshotStore.DEFAULT_MAX_QUERY_CACHE_WEIGHT_BYTES,
                2,
                (id, descriptor) -> storeRef.get().publish(
                        id,
                        "snapshot-race-" + changes.incrementAndGet(),
                        List.of(FileSymbolSnapshotStoreTest.symbols(id).get(0))));
        storeRef.set(store);
        store.publish(projectId, "snapshot-initial", List.of(FileSymbolSnapshotStoreTest.symbols(projectId).get(0)));

        IOException exception = assertThrows(IOException.class, () -> store.loadActiveQueryView(projectId));

        assertTrue(exception.getMessage().contains("retry limit exceeded"));
        assertEquals(3, changes.get());
        assertEquals(3, store.cacheStats().queryViewBuilds());
    }
}
