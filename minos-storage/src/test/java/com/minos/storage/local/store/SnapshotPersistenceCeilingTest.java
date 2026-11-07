package com.minos.storage.local.store;

import com.minos.storage.codec.PersistedSizeFixtures;
import com.minos.storage.codec.SnapshotCodecV3;
import com.minos.storage.codec.SnapshotLimits;
import com.minos.store.CodeKnowledgeSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A snapshot between the former and the current persisted ceiling is published and read back by the file store,
 * and the ceiling that refused it before is the one that refuses its read after a rollback. The two ceilings are
 * lowered stand-ins (16 and 24 MiB) for 256 and 400 MiB: the store logic is the same, the test stays small.
 */
class SnapshotPersistenceCeilingTest {

    private static final long FORMER = 16L * 1024L * 1024L;
    private static final long CURRENT = 24L * 1024L * 1024L;
    private static final SnapshotLimits FORMER_LIMITS = SnapshotLimits.production().withMaxPersistedBytes(FORMER);
    private static final SnapshotLimits CURRENT_LIMITS = SnapshotLimits.production().withMaxPersistedBytes(CURRENT);

    /** About 17 MB in V3: above {@link #FORMER}, below {@link #CURRENT}. */
    private static CodeKnowledgeSnapshot betweenTheTwoCeilings(UUID projectId, String snapshotId) throws IOException {
        CodeKnowledgeSnapshot snapshot = PersistedSizeFixtures.withTail(projectId, snapshotId, 2, 1_000_000);
        long encoded = new SnapshotCodecV3().encodedSize(snapshot);
        assertTrue(encoded > FORMER && encoded <= CURRENT, "fixture must sit between the ceilings: " + encoded);
        return snapshot;
    }

    @Test
    void aSnapshotAboveTheFormerCeilingIsPublishedUnderTheCurrentOneAndReadBack(@TempDir Path root) throws Exception {
        UUID projectId = UUID.randomUUID();
        CodeKnowledgeSnapshot snapshot = betweenTheTwoCeilings(projectId, "between");
        FileSymbolSnapshotStore store = new FileSymbolSnapshotStore(root, CURRENT_LIMITS);

        store.publish(projectId, "between", snapshot.symbols(), snapshot.occurrences(), snapshot.relationships());

        assertEquals("between", store.activeSnapshotId(projectId).orElseThrow());
        assertEquals(snapshot, store.loadActiveKnowledge(projectId).orElseThrow());
        assertEquals(snapshot, new FileSymbolSnapshotStore(root, CURRENT_LIMITS).loadActiveKnowledge(projectId).orElseThrow(),
                "a fresh store reads it from the file, verified by its SHA-256");
    }

    @Test
    void theFormerCeilingStillRefusesTheSameSnapshotWithoutWritingAnything(@TempDir Path root) throws Exception {
        UUID projectId = UUID.randomUUID();
        CodeKnowledgeSnapshot snapshot = betweenTheTwoCeilings(projectId, "between");
        FileSymbolSnapshotStore store = new FileSymbolSnapshotStore(root, FORMER_LIMITS);

        IOException refused = assertThrows(IOException.class, () -> store.publish(
                projectId, "between", snapshot.symbols(), snapshot.occurrences(), snapshot.relationships()));

        PersistedSnapshotLimitTest.assertRefusalMessage(refused.getMessage(), FORMER);
        assertTrue(store.activeSnapshotId(projectId).isEmpty());
    }

    @Test
    void theReadFollowsTheSameCeilingAsThePublication(@TempDir Path root) throws Exception {
        UUID projectId = UUID.randomUUID();
        CodeKnowledgeSnapshot snapshot = betweenTheTwoCeilings(projectId, "between");
        new FileSymbolSnapshotStore(root, CURRENT_LIMITS).publish(
                projectId, "between", snapshot.symbols(), snapshot.occurrences(), snapshot.relationships());

        FileSymbolSnapshotStore reader = new FileSymbolSnapshotStore(root, FORMER_LIMITS);

        IOException refused = assertThrows(IOException.class, () -> reader.loadActiveKnowledge(projectId));

        assertTrue(refused.getMessage().startsWith("snapshot payload exceeds persisted byte limit"), refused.getMessage());
        assertFalse(refused.getMessage().contains(root.toString()), "the refusal must not carry a path");
    }
}
