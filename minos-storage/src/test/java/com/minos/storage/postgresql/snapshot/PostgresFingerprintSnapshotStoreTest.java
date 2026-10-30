package com.minos.storage.postgresql.snapshot;

import com.minos.storage.postgresql.PostgresTestSupport;
import com.minos.storage.postgresql.support.PostgresJsonCodec;
import com.minos.incremental.FileFingerprint;
import com.minos.incremental.ProjectFingerprint;
import com.minos.incremental.ProjectFingerprintSnapshot;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresFingerprintSnapshotStoreTest extends PostgresTestSupport {

    @Test
    void publishesAndLoadsActiveSnapshot() throws Exception {
        UUID projectId = UUID.randomUUID();
        PostgresFingerprintSnapshotStore store = new PostgresFingerprintSnapshotStore(connections, new PostgresJsonCodec());
        ProjectFingerprintSnapshot snapshot = fingerprint(projectId, "snap-1");

        store.publish(projectId, "snap-1", snapshot.fingerprint());
        store.promote(projectId, "snap-1");

        Optional<ProjectFingerprintSnapshot> loaded = store.loadActive(projectId);
        assertTrue(loaded.isPresent());
        assertEquals(snapshot, loaded.get());
    }

    @Test
    void isIdempotentForSameContent() throws Exception {
        UUID projectId = UUID.randomUUID();
        PostgresFingerprintSnapshotStore store = new PostgresFingerprintSnapshotStore(connections, new PostgresJsonCodec());
        ProjectFingerprintSnapshot snapshot = fingerprint(projectId, "snap-1");

        store.publish(projectId, "snap-1", snapshot.fingerprint());
        ProjectFingerprintSnapshot second = store.publish(projectId, "snap-1", snapshot.fingerprint());

        // AUD-TST-09: the second publication is re-read, not merely survived.
        assertEquals(snapshot, second);
        assertEquals(1L, rowCount("fingerprint_snapshots", projectId), "one stored fingerprint row");
        PostgresFingerprintSnapshotStore reloaded = new PostgresFingerprintSnapshotStore(connections, new PostgresJsonCodec());
        assertEquals(Optional.of(snapshot), reloaded.load(projectId, "snap-1"));
        reloaded.promote(projectId, "snap-1");
        assertEquals(1L, rowCount("fingerprint_active", projectId), "one active fingerprint row");
        assertEquals(Optional.of(snapshot), reloaded.loadActive(projectId));
    }

    private long rowCount(String table, UUID projectId) throws Exception {
        return connections.withConnection(c -> {
            try (PreparedStatement s = c.prepareStatement("SELECT count(*) FROM " + table + " WHERE project_id=?")) {
                s.setObject(1, projectId);
                try (ResultSet result = s.executeQuery()) {
                    result.next();
                    return result.getLong(1);
                }
            }
        });
    }

    @Test
    void rejectsIdentityMutationWithDifferentContent() throws Exception {
        UUID projectId = UUID.randomUUID();
        PostgresFingerprintSnapshotStore store = new PostgresFingerprintSnapshotStore(connections, new PostgresJsonCodec());
        store.publish(projectId, "snap-1", fingerprint(projectId, "snap-1").fingerprint());

        ProjectFingerprint mutated = new ProjectFingerprint(
                "b".repeat(64), "c".repeat(64),
                List.of(new FileFingerprint("other.java", 200, "d".repeat(64))));

        IOException exception = assertThrows(IOException.class,
                () -> store.publish(projectId, "snap-1", mutated));
        assertTrue(exception.getMessage().contains("different content"),
                "exception must describe the identity conflict: " + exception.getMessage());
    }

    @Test
    void promoteRejectsUnpublishedSnapshot() throws Exception {
        UUID projectId = UUID.randomUUID();
        PostgresFingerprintSnapshotStore store = new PostgresFingerprintSnapshotStore(connections, new PostgresJsonCodec());

        assertThrows(IOException.class, () -> store.promote(projectId, "snap-never-published"));
    }

    @Test
    void returnsEmptyForUnknownProject() throws Exception {
        PostgresFingerprintSnapshotStore store = new PostgresFingerprintSnapshotStore(connections, new PostgresJsonCodec());
        assertTrue(store.loadActive(UUID.randomUUID()).isEmpty());
    }

    private static ProjectFingerprintSnapshot fingerprint(UUID projectId, String snapshotId) {
        ProjectFingerprint fp = new ProjectFingerprint(
                "a".repeat(64), "b".repeat(64),
                List.of(new FileFingerprint("Service.java", 1024, "c".repeat(64))));
        return new ProjectFingerprintSnapshot(projectId, snapshotId, fp);
    }
}
