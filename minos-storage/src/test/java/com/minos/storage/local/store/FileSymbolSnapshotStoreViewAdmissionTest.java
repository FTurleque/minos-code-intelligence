package com.minos.storage.local.store;

import com.minos.domain.Symbol;
import com.minos.storage.codec.PersistedSizeFixtures;
import com.minos.storage.codec.SnapshotCodecV1;
import com.minos.storage.codec.SnapshotCodecV2;
import com.minos.storage.codec.SnapshotCodecV3;
import com.minos.storage.codec.SnapshotLimits;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.SnapshotReadView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Admission of a view to the cache of the file store follows the estimated weight of its format: a V3 snapshot
 * (measured amplification) fits a cache that a V1 or V2 snapshot of the same shape (conservative amplification)
 * does not, and is held alone, still counted, above it. The cache ceiling is lowered to 1.5 times the persisted size,
 * between the two estimates, instead of building a snapshot of the production size.
 */
class FileSymbolSnapshotStoreViewAdmissionTest {

    /** About 17 MB in V3, 34 MB in V2 and V1: three large ASCII strings and no relationship. */
    private static CodeKnowledgeSnapshot snapshot(UUID projectId, String snapshotId) {
        return PersistedSizeFixtures.withTail(projectId, snapshotId, 2, 1_000_000);
    }

    private static long cacheCeilingBetweenTheEstimates(long persistedBytes) {
        return persistedBytes * 3L / 2L;
    }

    @Test
    void aV3ViewWhoseEstimatedWeightFitsTheCacheIsLoadedOnceForTwoReads(@TempDir Path root) throws Exception {
        UUID projectId = UUID.randomUUID();
        CodeKnowledgeSnapshot snapshot = snapshot(projectId, "v3");
        long persisted = new SnapshotCodecV3().encodedSize(snapshot);
        FileSymbolSnapshotStore store = new FileSymbolSnapshotStore(root, 4, cacheCeilingBetweenTheEstimates(persisted));
        store.publish(projectId, "v3", snapshot.symbols(), snapshot.occurrences(), snapshot.relationships());

        SnapshotReadView first = store.loadActiveReadView(projectId).orElseThrow();
        SnapshotReadView second = store.loadActiveReadView(projectId).orElseThrow();

        assertEquals(3, first.descriptor().formatVersion());
        assertSame(first, second);
        assertEquals(1, store.cacheStats().fullSnapshotLoads());
        assertEquals(1, store.cacheStats().entries());
        assertEquals(Math.ceilDiv(persisted * 125L, 100L)
                        + SnapshotReadView.incomingRelationshipsWeightBytes(0L),
                store.cacheStats().weightBytes(), "the estimate is the measured amplification of the file");
    }

    @Test
    void aV2ViewIsEstimatedWithTheConservativeAmplificationAndIsHeldAloneWhereV3IsCached(@TempDir Path root) throws Exception {
        UUID projectId = UUID.randomUUID();
        List<Symbol> symbols = new ArrayList<>(snapshot(projectId, "v2").symbols());
        // An unpaired surrogate has no UTF-8 form: the store keeps the snapshot whole, in V2.
        symbols.add(PersistedSizeFixtures.symbol(projectId, 99, "orphelin" + (char) 0xD800));
        CodeKnowledgeSnapshot snapshot = new CodeKnowledgeSnapshot(projectId, "v2", symbols, List.of(), List.of());
        long persisted = new SnapshotCodecV2().encodedSize(snapshot);
        FileSymbolSnapshotStore store = new FileSymbolSnapshotStore(root, 4, cacheCeilingBetweenTheEstimates(persisted));
        store.publish(projectId, "v2", snapshot.symbols(), snapshot.occurrences(), snapshot.relationships());

        SnapshotReadView first = store.loadActiveReadView(projectId).orElseThrow();
        SnapshotReadView second = store.loadActiveReadView(projectId).orElseThrow();

        assertEquals(2, first.descriptor().formatVersion());
        assertSame(first, second);
        assertEquals(1, store.cacheStats().fullSnapshotLoads(), "the held view serves the second read");
        assertEquals(0, store.cacheStats().entries(), "unmeasured format: the view is above the cache ceiling");
        assertTrue(store.cacheStats().heldWeightBytes() > cacheCeilingBetweenTheEstimates(persisted));
    }

    @Test
    void aV1ViewIsEstimatedWithTheConservativeAmplificationAndIsHeldAloneWhereV3IsCached(@TempDir Path root) throws Exception {
        UUID projectId = UUID.randomUUID();
        CodeKnowledgeSnapshot snapshot = snapshot(projectId, "v1");
        long persisted = new SnapshotCodecV1().encodedSize(snapshot);
        FileSymbolSnapshotStore store = new FileSymbolSnapshotStore(root, 4, cacheCeilingBetweenTheEstimates(persisted));
        store.publish(projectId, "v1", snapshot.symbols());

        SnapshotReadView first = store.loadActiveReadView(projectId).orElseThrow();
        SnapshotReadView second = store.loadActiveReadView(projectId).orElseThrow();

        assertEquals(1, first.descriptor().formatVersion());
        assertSame(first, second);
        assertEquals(1, store.cacheStats().fullSnapshotLoads());
        assertEquals(0, store.cacheStats().entries());
        assertTrue(store.cacheStats().heldWeightBytes() > cacheCeilingBetweenTheEstimates(persisted));
    }

    @Test
    void aViewHeavierThanTheCacheCeilingIsHeldAloneWhateverItsFormat(@TempDir Path root) throws Exception {
        UUID projectId = UUID.randomUUID();
        CodeKnowledgeSnapshot snapshot = snapshot(projectId, "v3");
        long persisted = new SnapshotCodecV3().encodedSize(snapshot);
        // Below the measured estimate of the view: held alone, counted, and still read correctly.
        FileSymbolSnapshotStore store = new FileSymbolSnapshotStore(root, 4, persisted);
        store.publish(projectId, "v3", snapshot.symbols(), snapshot.occurrences(), snapshot.relationships());

        SnapshotReadView first = store.loadActiveReadView(projectId).orElseThrow();
        SnapshotReadView second = store.loadActiveReadView(projectId).orElseThrow();

        assertEquals(1, store.cacheStats().fullSnapshotLoads());
        assertEquals(0, store.cacheStats().entries());
        assertSame(first, second, "the held view serves the second read");
        assertTrue(store.cacheStats().heldWeightBytes() > persisted);
        assertEquals(snapshot, second.snapshot());
    }

    @Test
    void theLimitsGiveTheWeightTheStoreEstimatesForASnapshotOfTheCeilingSize(@TempDir Path root) throws Exception {
        UUID projectId = UUID.randomUUID();
        CodeKnowledgeSnapshot snapshot = PersistedSizeFixtures.withTail(projectId, "exact", 0, 200_000);
        SnapshotLimits limits = SnapshotLimits.production()
                .withMaxPersistedBytes(new SnapshotCodecV3().encodedSize(snapshot));
        FileSymbolSnapshotStore store = new FileSymbolSnapshotStore(root, limits);
        store.publish(projectId, "exact", snapshot.symbols(), snapshot.occurrences(), snapshot.relationships());

        store.loadActiveReadView(projectId).orElseThrow();

        assertEquals(limits.largestPersistableViewWeightBytes(snapshot.symbols().size(), 0L, 0L),
                store.cacheStats().weightBytes());
    }
}
