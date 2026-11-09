package com.minos.storage.postgresql.snapshot;

import com.minos.storage.codec.SnapshotLimits;
import com.minos.storage.postgresql.PostgresTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The PostgreSQL store applies the same limits as the file store, and they satisfy the cache invariant. */
class PostgresCodeKnowledgeSnapshotStoreLimitsTest extends PostgresTestSupport {

    @TempDir Path tempDir;

    @Test
    void theCacheCeilingCoversTheLargestPersistableSnapshot() throws Exception {
        PostgresCodeKnowledgeSnapshotStore store = new PostgresCodeKnowledgeSnapshotStore(connections, tempDir);

        assertEquals(SnapshotLimits.production(), store.limits());
        assertEquals(store.limits().maxQueryCacheWeightBytes(), store.queryCacheStats().maximumWeightBytes());
        assertDoesNotThrow(store.limits()::requireCacheCoversPersistable);
    }

    @Test
    void aCacheCeilingBelowThePersistableViewWeightFailsNamingTheThreeValues() throws Exception {
        SnapshotLimits reduced = SnapshotLimits.production().withMaxQueryCacheWeightBytes(64L * 1024L * 1024L);
        PostgresCodeKnowledgeSnapshotStore store = new PostgresCodeKnowledgeSnapshotStore(connections, tempDir, reduced);

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                store.limits()::requireCacheCoversPersistable);

        assertTrue(refused.getMessage().contains(String.valueOf(reduced.maxQueryCacheWeightBytes())), refused.getMessage());
        assertTrue(refused.getMessage().contains(String.valueOf(reduced.maxPersistedBytes())), refused.getMessage());
        assertTrue(refused.getMessage().contains(String.valueOf(reduced.currentFormatAmplificationPercent())),
                refused.getMessage());
    }
}
