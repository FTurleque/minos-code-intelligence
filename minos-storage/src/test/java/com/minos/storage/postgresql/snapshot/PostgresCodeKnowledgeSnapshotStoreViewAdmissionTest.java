package com.minos.storage.postgresql.snapshot;

import com.minos.domain.Symbol;
import com.minos.storage.codec.PersistedSizeFixtures;
import com.minos.storage.codec.SnapshotCodecV2;
import com.minos.storage.codec.SnapshotCodecV3;
import com.minos.storage.codec.SnapshotLimits;
import com.minos.storage.postgresql.PostgresTestSupport;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.SnapshotReadView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Admission of a view to the cache of the PostgreSQL store follows the estimated weight of its format, as in the
 * file store: a V3 payload (measured amplification) fits a cache that a V2 payload of the same shape
 * (conservative amplification) does not. The cache ceiling is lowered to 1.5 times the payload size, between
 * the two estimates. A cache miss is a full load of the payload: the store has no other load counter.
 */
class PostgresCodeKnowledgeSnapshotStoreViewAdmissionTest extends PostgresTestSupport {

    @TempDir Path tempDir;

    /** About 17 MB in V3 and 34 MB in V2: three large ASCII strings and no relationship. */
    private static List<Symbol> symbols(UUID projectId) {
        return PersistedSizeFixtures.withTail(projectId, "admission", 2, 1_000_000).symbols();
    }

    private PostgresCodeKnowledgeSnapshotStore storeWithCacheCeiling(long ceiling) throws Exception {
        return new PostgresCodeKnowledgeSnapshotStore(
                connections, tempDir, SnapshotLimits.production().withMaxQueryCacheWeightBytes(ceiling));
    }

    @Test
    void aV3ViewWhoseEstimatedWeightFitsTheCacheIsLoadedOnceForTwoReads() throws Exception {
        UUID projectId = UUID.randomUUID();
        List<Symbol> symbols = symbols(projectId);
        long persisted = new SnapshotCodecV3().encodedSize(
                new CodeKnowledgeSnapshot(projectId, "v3", symbols, List.of(), List.of()));
        PostgresCodeKnowledgeSnapshotStore store = storeWithCacheCeiling(persisted * 3L / 2L);
        store.publish(projectId, "v3", symbols, List.of(), List.of());

        SnapshotReadView first = store.loadActiveReadView(projectId).orElseThrow();
        SnapshotReadView second = store.loadActiveReadView(projectId).orElseThrow();

        assertEquals(3, first.descriptor().formatVersion());
        assertSame(first, second);
        assertEquals(1L, store.queryCacheStats().misses(), "one full load");
        assertEquals(1L, store.queryCacheStats().hits());
        assertEquals(1, store.queryCacheStats().entries());
        assertEquals(Math.ceilDiv(persisted * 125L, 100L) + SnapshotReadView.incomingRelationshipsWeightBytes(0L),
                store.queryCacheStats().weightBytes(), "the estimate is the measured amplification of the payload");
    }

    @Test
    void aV2ViewIsEstimatedWithTheConservativeAmplificationAndIsNotAdmittedWhereV3Is() throws Exception {
        UUID projectId = UUID.randomUUID();
        List<Symbol> symbols = new ArrayList<>(symbols(projectId));
        // An unpaired surrogate has no UTF-8 form: the store keeps the snapshot whole, in V2.
        symbols.add(PersistedSizeFixtures.symbol(projectId, 99, "orphelin" + (char) 0xD800));
        long persisted = new SnapshotCodecV2().encodedSize(
                new CodeKnowledgeSnapshot(projectId, "v2", symbols, List.of(), List.of()));
        PostgresCodeKnowledgeSnapshotStore store = storeWithCacheCeiling(persisted * 3L / 2L);
        store.publish(projectId, "v2", symbols, List.of(), List.of());

        SnapshotReadView first = store.loadActiveReadView(projectId).orElseThrow();
        store.loadActiveReadView(projectId).orElseThrow();

        assertEquals(2, first.descriptor().formatVersion());
        assertEquals(2L, store.queryCacheStats().misses(), "unmeasured format: reloaded on every read");
        assertEquals(0, store.queryCacheStats().entries());
    }

    @Test
    void aViewHeavierThanTheCacheCeilingIsNotCachedAndStillReadCorrectly() throws Exception {
        UUID projectId = UUID.randomUUID();
        List<Symbol> symbols = symbols(projectId);
        CodeKnowledgeSnapshot snapshot = new CodeKnowledgeSnapshot(projectId, "heavy", symbols, List.of(), List.of());
        long persisted = new SnapshotCodecV3().encodedSize(snapshot);
        // Below the measured estimate of the view: refused by the cache.
        PostgresCodeKnowledgeSnapshotStore store = storeWithCacheCeiling(persisted);
        store.publish(projectId, "heavy", symbols, List.of(), List.of());

        SnapshotReadView first = store.loadActiveReadView(projectId).orElseThrow();
        SnapshotReadView second = store.loadActiveReadView(projectId).orElseThrow();

        assertEquals(2L, store.queryCacheStats().misses());
        assertEquals(0L, store.queryCacheStats().hits());
        assertEquals(0, store.queryCacheStats().entries());
        assertNotSame(first, second, "a view that is not admitted is rebuilt");
        assertEquals(snapshot, second.snapshot());
    }
}
