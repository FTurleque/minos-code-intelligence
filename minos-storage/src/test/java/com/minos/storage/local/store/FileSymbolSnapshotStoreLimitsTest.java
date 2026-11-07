package com.minos.storage.local.store;

import com.minos.storage.codec.SnapshotLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The file store applies the production limits unless given others, and they satisfy the cache invariant. */
class FileSymbolSnapshotStoreLimitsTest {

    @Test
    void theCacheCeilingCoversTheLargestPersistableSnapshot(@TempDir Path root) throws Exception {
        FileSymbolSnapshotStore store = new FileSymbolSnapshotStore(root);

        assertEquals(SnapshotLimits.production(), store.limits());
        assertEquals(FileSymbolSnapshotStore.DEFAULT_MAX_QUERY_CACHE_WEIGHT_BYTES,
                store.limits().maxQueryCacheWeightBytes());
        assertDoesNotThrow(store.limits()::requireCacheCoversPersistable);
    }

    @Test
    void aCacheCeilingBelowThePersistableViewWeightFailsNamingTheThreeValues(@TempDir Path root) throws Exception {
        SnapshotLimits reduced = SnapshotLimits.production().withMaxQueryCacheWeightBytes(64L * 1024L * 1024L);
        FileSymbolSnapshotStore store = new FileSymbolSnapshotStore(root, reduced);

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                store.limits()::requireCacheCoversPersistable);

        assertTrue(refused.getMessage().contains(String.valueOf(reduced.maxQueryCacheWeightBytes())), refused.getMessage());
        assertTrue(refused.getMessage().contains(String.valueOf(reduced.maxPersistedBytes())), refused.getMessage());
        assertTrue(refused.getMessage().contains(String.valueOf(reduced.currentFormatAmplificationPercent())),
                refused.getMessage());
    }
}
