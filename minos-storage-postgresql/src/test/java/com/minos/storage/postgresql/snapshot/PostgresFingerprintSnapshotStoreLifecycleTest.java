package com.minos.storage.postgresql.snapshot;

import com.minos.incremental.FileFingerprint;
import com.minos.incremental.ProjectFingerprint;
import com.minos.incremental.ProjectFingerprintSnapshot;
import com.minos.storage.postgresql.PostgresTestSupport;
import com.minos.storage.postgresql.support.PostgresConnectionFactory;
import com.minos.storage.postgresql.support.PostgresJsonCodec;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PostgresFingerprintSnapshotStoreLifecycleTest extends PostgresTestSupport {

    @Test
    void aPublishedSnapshotIsLoadableByIdButNotActiveUntilPromoted() throws Exception {
        UUID projectId = UUID.randomUUID();
        PostgresFingerprintSnapshotStore store = store();

        ProjectFingerprintSnapshot published = store.publish(projectId, "snap-1", fingerprint("a"));

        assertEquals(new ProjectFingerprintSnapshot(projectId, "snap-1", fingerprint("a")), published);
        assertEquals(Optional.of(published), store().load(projectId, "snap-1"));
        assertEquals(Optional.empty(), store.load(projectId, "snap-2"));
        assertEquals(Optional.empty(), store.load(UUID.randomUUID(), "snap-1"), "a snapshot belongs to one project");
        assertEquals(Optional.empty(), store.loadActive(projectId), "publication alone must not activate a snapshot");
    }

    @Test
    void promotionMovesTheActivePointerAndKeepsEarlierSnapshotsLoadable() throws Exception {
        UUID projectId = UUID.randomUUID();
        PostgresFingerprintSnapshotStore store = store();
        ProjectFingerprintSnapshot first = store.publish(projectId, "snap-1", fingerprint("a"));
        ProjectFingerprintSnapshot second = store.publish(projectId, "snap-2", fingerprint("b"));

        store.promote(projectId, "snap-1");
        assertEquals(Optional.of(first), store.loadActive(projectId));
        store.promote(projectId, "snap-2");
        assertEquals(Optional.of(second), store.loadActive(projectId));
        store.promote(projectId, "snap-1");

        assertEquals(Optional.of(first), store.loadActive(projectId), "an earlier snapshot can be promoted again");
        assertEquals(Optional.of(second), store.load(projectId, "snap-2"));
    }

    @Test
    void aRefusedPromotionKeepsThePreviousActiveSnapshot() throws Exception {
        UUID projectId = UUID.randomUUID();
        PostgresFingerprintSnapshotStore store = store();
        ProjectFingerprintSnapshot first = store.publish(projectId, "snap-1", fingerprint("a"));
        store.promote(projectId, "snap-1");

        IOException refusal = assertThrows(IOException.class, () -> store.promote(projectId, "snap-missing"));

        assertEquals("fingerprint snapshot is not published: snap-missing", refusal.getMessage());
        assertEquals(Optional.of(first), store.loadActive(projectId));
    }

    @Test
    void aRefusedRepublicationKeepsTheStoredContentAndReturnsItOnAnIdenticalOne() throws Exception {
        UUID projectId = UUID.randomUUID();
        PostgresFingerprintSnapshotStore store = store();
        ProjectFingerprintSnapshot original = store.publish(projectId, "snap-1", fingerprint("a"));
        ProjectFingerprint other = fingerprint("b");

        IOException refusal = assertThrows(IOException.class, () -> store.publish(projectId, "snap-1", other));

        assertEquals("fingerprint snapshot already exists with different content: snap-1", refusal.getMessage());
        assertEquals(Optional.of(original), store.load(projectId, "snap-1"));
        assertEquals(original, store.publish(projectId, "snap-1", fingerprint("a")));
        assertEquals(List.of("snap-1"), store.listIndexSnapshotIds(projectId));
    }

    @Test
    void snapshotIdsAreListedSortedAndPerProject() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID otherProject = UUID.randomUUID();
        PostgresFingerprintSnapshotStore store = store();
        store.publish(projectId, "snap-c", fingerprint("c"));
        store.publish(projectId, "snap-a", fingerprint("a"));
        store.publish(projectId, "snap-b", fingerprint("b"));
        store.publish(otherProject, "snap-z", fingerprint("d"));

        assertEquals(List.of("snap-a", "snap-b", "snap-c"), store.listIndexSnapshotIds(projectId));
        assertEquals(List.of("snap-z"), store.listIndexSnapshotIds(otherProject));
        assertEquals(List.of(), store.listIndexSnapshotIds(UUID.randomUUID()));
    }

    @Test
    void aPayloadThatCannotBeDecodedFailsTheReadInsteadOfBeingReportedAbsent() throws Exception {
        UUID projectId = UUID.randomUUID();
        PostgresFingerprintSnapshotStore store = store();
        store.publish(projectId, "snap-1", fingerprint("a"));
        store.promote(projectId, "snap-1");
        connections.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE fingerprint_snapshots SET payload=CAST('{\"fingerprint\":42}' AS jsonb) WHERE project_id=?")) {
                statement.setObject(1, projectId);
                return statement.executeUpdate();
            }
        });
        ProjectFingerprint replacement = fingerprint("b");

        assertThrows(IOException.class, () -> store.load(projectId, "snap-1"));
        assertThrows(IOException.class, () -> store.loadActive(projectId));
        assertThrows(IOException.class, () -> store.publish(projectId, "snap-1", replacement),
                "an undecodable stored snapshot must not be silently replaced");
        assertThrows(IOException.class, () -> store.promote(projectId, "snap-1"));
    }

    @Test
    void aStorageFailureIsReportedAsAnIoExceptionNamingTheOperation() throws Exception {
        PostgresConnectionFactory closed = createFactory("minos");
        closed.close();
        PostgresFingerprintSnapshotStore store = new PostgresFingerprintSnapshotStore(closed, new PostgresJsonCodec());
        UUID projectId = UUID.randomUUID();
        ProjectFingerprint fingerprint = fingerprint("a");

        IOException publish = assertThrows(IOException.class, () -> store.publish(projectId, "snap-1", fingerprint));
        IOException promote = assertThrows(IOException.class, () -> store.promote(projectId, "snap-1"));
        IOException load = assertThrows(IOException.class, () -> store.load(projectId, "snap-1"));
        IOException loadActive = assertThrows(IOException.class, () -> store.loadActive(projectId));
        IOException list = assertThrows(IOException.class, () -> store.listIndexSnapshotIds(projectId));

        assertEquals("unable to publish PostgreSQL fingerprint snapshot", publish.getMessage());
        assertEquals("unable to promote PostgreSQL fingerprint snapshot", promote.getMessage());
        assertEquals("unable to load PostgreSQL fingerprint snapshot", load.getMessage());
        assertEquals("unable to load active PostgreSQL fingerprint snapshot", loadActive.getMessage());
        assertEquals("unable to list PostgreSQL fingerprint snapshots", list.getMessage());
        for (IOException failure : List.of(publish, promote, load, loadActive, list)) {
            assertInstanceOf(SQLException.class, failure.getCause());
        }
        assertEquals(List.of(), store().listIndexSnapshotIds(projectId), "nothing may be written through a closed pool");
    }

    private PostgresFingerprintSnapshotStore store() {
        return new PostgresFingerprintSnapshotStore(connections, new PostgresJsonCodec());
    }

    /** A distinct, valid fingerprint per seed character ({@code a}..{@code d}). */
    private static ProjectFingerprint fingerprint(String seed) {
        return new ProjectFingerprint(seed.repeat(64), "e".repeat(64),
                List.of(new FileFingerprint("Service-" + seed + ".java", 1024, "f".repeat(64))));
    }
}
