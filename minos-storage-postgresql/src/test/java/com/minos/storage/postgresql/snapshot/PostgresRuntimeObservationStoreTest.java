package com.minos.storage.postgresql.snapshot;

import com.minos.dynamic.CorrelatedRuntimeObservation;
import com.minos.dynamic.CorrelatedRuntimeSession;
import com.minos.dynamic.RuntimeObservation;
import com.minos.dynamic.RuntimeObservationCompleteness;
import com.minos.dynamic.RuntimeObservationSession;
import com.minos.dynamic.RuntimeObservationStore;
import com.minos.dynamic.RuntimeObservationType;
import com.minos.dynamic.RuntimeResolutionStatus;
import com.minos.dynamic.RuntimeSymbolReference;
import com.minos.dynamic.RuntimeSymbolResolution;
import com.minos.storage.postgresql.PostgresTestSupport;
import com.minos.storage.postgresql.support.PostgresConnectionFactory;
import com.minos.storage.postgresql.support.PostgresJsonCodec;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresRuntimeObservationStoreTest extends PostgresTestSupport {
    private static final Instant IMPORTED_AT = Instant.parse("2026-07-29T07:00:00Z");

    @Test
    void savesASessionAndFindsItBackUnchanged() throws Exception {
        UUID projectId = UUID.randomUUID();
        PostgresRuntimeObservationStore store = store();
        CorrelatedRuntimeSession session = session(projectId, "run-1", "snapshot-1", IMPORTED_AT, "a".repeat(64));

        RuntimeObservationStore.SaveResult saved = store.save(session);

        assertFalse(saved.alreadyPresent());
        assertEquals(session, saved.session());
        assertEquals(Optional.of(session), store().find(projectId, "run-1"),
                "a second store instance must read the session from PostgreSQL, not from memory");
        assertEquals(Optional.empty(), store.find(projectId, "run-2"));
        assertEquals(Optional.empty(), store.find(UUID.randomUUID(), "run-1"), "a session belongs to one project");
        assertEquals(1, storedRows(projectId));
    }

    @Test
    void savingTheSameSourceAgainIsIdempotentAndReturnsTheStoredSession() throws Exception {
        UUID projectId = UUID.randomUUID();
        PostgresRuntimeObservationStore store = store();
        CorrelatedRuntimeSession first = session(projectId, "run-1", "snapshot-1", IMPORTED_AT, "a".repeat(64));
        CorrelatedRuntimeSession replayedLater = session(
                projectId, "run-1", "snapshot-1", IMPORTED_AT.plusSeconds(3_600), "a".repeat(64));
        store.save(first);

        RuntimeObservationStore.SaveResult replay = store.save(replayedLater);

        assertTrue(replay.alreadyPresent());
        assertEquals(first, replay.session(), "the stored session wins over the replayed one");
        assertEquals(IMPORTED_AT, store.find(projectId, "run-1").orElseThrow().importedAt());
        assertEquals(1, storedRows(projectId));
    }

    @Test
    void refusesToReplaceAnExistingSessionWithDifferentContent() throws Exception {
        UUID projectId = UUID.randomUUID();
        PostgresRuntimeObservationStore store = store();
        CorrelatedRuntimeSession original = session(projectId, "run-1", "snapshot-1", IMPORTED_AT, "a".repeat(64));
        CorrelatedRuntimeSession mutated = session(projectId, "run-1", "snapshot-2", IMPORTED_AT, "b".repeat(64));
        store.save(original);

        IOException refusal = assertThrows(IOException.class, () -> store.save(mutated));

        assertEquals("runtime session is immutable and already exists with different content: run-1",
                refusal.getMessage());
        assertEquals(Optional.of(original), store.find(projectId, "run-1"), "the refused content must not be stored");
        assertEquals(1, storedRows(projectId));
    }

    @Test
    void theSameSessionIdInAnotherProjectIsAnIndependentSession() throws Exception {
        UUID projectA = UUID.randomUUID();
        UUID projectB = UUID.randomUUID();
        PostgresRuntimeObservationStore store = store();
        CorrelatedRuntimeSession inA = session(projectA, "run-1", "snapshot-1", IMPORTED_AT, "a".repeat(64));
        CorrelatedRuntimeSession inB = session(projectB, "run-1", "snapshot-1", IMPORTED_AT, "b".repeat(64));

        store.save(inA);
        RuntimeObservationStore.SaveResult second = store.save(inB);

        assertFalse(second.alreadyPresent());
        assertEquals(List.of(inA), store.list(projectA));
        assertEquals(List.of(inB), store.list(projectB));
    }

    @Test
    void listsNewestImportFirstThenBySessionId() throws Exception {
        UUID projectId = UUID.randomUUID();
        PostgresRuntimeObservationStore store = store();
        store.save(session(projectId, "run-old", "snapshot-1", IMPORTED_AT, "a".repeat(64)));
        store.save(session(projectId, "run-b", "snapshot-2", IMPORTED_AT.plusSeconds(60), "b".repeat(64)));
        store.save(session(projectId, "run-a", "snapshot-2", IMPORTED_AT.plusSeconds(60), "c".repeat(64)));
        store.save(session(projectId, "run-new", "snapshot-1", IMPORTED_AT.plusSeconds(120), "d".repeat(64)));

        assertEquals(List.of("run-new", "run-a", "run-b", "run-old"), sessionIds(store.list(projectId)));
        assertEquals(List.of(), store.list(UUID.randomUUID()));
    }

    @Test
    void theBoundedListHonoursItsLimitAndItsSnapshotFilter() throws Exception {
        UUID projectId = UUID.randomUUID();
        PostgresRuntimeObservationStore store = store();
        store.save(session(projectId, "run-old", "snapshot-1", IMPORTED_AT, "a".repeat(64)));
        store.save(session(projectId, "run-b", "snapshot-2", IMPORTED_AT.plusSeconds(60), "b".repeat(64)));
        store.save(session(projectId, "run-a", "snapshot-2", IMPORTED_AT.plusSeconds(60), "c".repeat(64)));
        store.save(session(projectId, "run-new", "snapshot-1", IMPORTED_AT.plusSeconds(120), "d".repeat(64)));

        assertEquals(List.of("run-new", "run-a"), sessionIds(store.list(projectId, null, 2)));
        assertEquals(List.of("run-new", "run-a", "run-b", "run-old"), sessionIds(store.list(projectId, null, 100)));
        assertEquals(List.of("run-a", "run-b"), sessionIds(store.list(projectId, "snapshot-2", 100)));
        assertEquals(List.of("run-new"), sessionIds(store.list(projectId, "snapshot-1", 1)));
        assertEquals(List.of(), store.list(projectId, "snapshot-unknown", 100));
        assertEquals(List.of(), store.list(UUID.randomUUID(), "snapshot-1", 100));
    }

    @Test
    void theBoundedListRefusesANonPositiveLimitBeforeQuerying() throws Exception {
        PostgresConnectionFactory closed = closedFactory();
        PostgresRuntimeObservationStore store = new PostgresRuntimeObservationStore(closed, new PostgresJsonCodec());
        UUID projectId = UUID.randomUUID();

        IllegalArgumentException zero = assertThrows(IllegalArgumentException.class,
                () -> store.list(projectId, null, 0));
        IllegalArgumentException negative = assertThrows(IllegalArgumentException.class,
                () -> store.list(projectId, "snapshot-1", -1));

        // A closed pool would have failed with an IOException: the limit is checked before any connection is used.
        assertEquals("runtime session limit must be positive", zero.getMessage());
        assertEquals("runtime session limit must be positive", negative.getMessage());
    }

    @Test
    void aStorageFailureIsReportedAsAnIoExceptionNamingTheOperation() throws Exception {
        PostgresConnectionFactory closed = closedFactory();
        PostgresRuntimeObservationStore store = new PostgresRuntimeObservationStore(closed, new PostgresJsonCodec());
        UUID projectId = UUID.randomUUID();
        CorrelatedRuntimeSession session = session(projectId, "run-1", "snapshot-1", IMPORTED_AT, "a".repeat(64));

        IOException save = assertThrows(IOException.class, () -> store.save(session));
        IOException find = assertThrows(IOException.class, () -> store.find(projectId, "run-1"));
        IOException list = assertThrows(IOException.class, () -> store.list(projectId));
        IOException bounded = assertThrows(IOException.class, () -> store.list(projectId, null, 10));

        assertEquals("unable to save PostgreSQL runtime session", save.getMessage());
        assertEquals("unable to find PostgreSQL runtime session", find.getMessage());
        assertEquals("unable to list PostgreSQL runtime sessions", list.getMessage());
        assertEquals("unable to list bounded PostgreSQL runtime sessions", bounded.getMessage());
        for (IOException failure : List.of(save, find, list, bounded)) {
            assertInstanceOf(SQLException.class, failure.getCause());
        }
        assertEquals(0, storedRows(projectId), "nothing may be written through a closed pool");
    }

    @Test
    void aPayloadThatCannotBeDecodedFailsTheReadInsteadOfBeingSkipped() throws Exception {
        UUID projectId = UUID.randomUUID();
        PostgresRuntimeObservationStore store = store();
        store.save(session(projectId, "run-1", "snapshot-1", IMPORTED_AT, "a".repeat(64)));
        connections.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE runtime_sessions SET payload=CAST('{\"session\":42}' AS jsonb) WHERE project_id=?")) {
                statement.setObject(1, projectId);
                return statement.executeUpdate();
            }
        });

        assertThrows(IOException.class, () -> store.find(projectId, "run-1"));
        assertThrows(IOException.class, () -> store.list(projectId));
        assertThrows(IOException.class, () -> store.list(projectId, null, 10));
    }

    private PostgresRuntimeObservationStore store() {
        return new PostgresRuntimeObservationStore(connections, new PostgresJsonCodec());
    }

    private PostgresConnectionFactory closedFactory() throws IOException {
        PostgresConnectionFactory factory = createFactory("minos");
        factory.close();
        return factory;
    }

    private int storedRows(UUID projectId) throws Exception {
        return connections.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT count(*) FROM runtime_sessions WHERE project_id=?")) {
                statement.setObject(1, projectId);
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    return result.getInt(1);
                }
            }
        });
    }

    private static List<String> sessionIds(List<CorrelatedRuntimeSession> sessions) {
        return sessions.stream().map(value -> value.session().sessionId()).toList();
    }

    private static CorrelatedRuntimeSession session(
            UUID projectId, String sessionId, String snapshotId, Instant importedAt, String sourceSha) {
        RuntimeSymbolReference reference = new RuntimeSymbolReference("key:service", null, null, null);
        RuntimeObservation observation = new RuntimeObservation(
                RuntimeObservationType.SYMBOL_EXECUTION, reference, null, 3, 100);
        RuntimeObservationSession raw = new RuntimeObservationSession(
                RuntimeObservationSession.FORMAT, sessionId, projectId, snapshotId,
                Instant.parse("2026-07-29T06:00:00Z"), Instant.parse("2026-07-29T06:01:00Z"),
                "fixture", "1", "test", RuntimeObservationCompleteness.PARTIAL, List.of(observation));
        RuntimeSymbolResolution resolution = new RuntimeSymbolResolution(
                RuntimeResolutionStatus.RESOLVED, reference, "symbol-1", "key:service",
                "com.acme.Service", List.of(), false);
        return new CorrelatedRuntimeSession(raw, importedAt, sourceSha,
                List.of(new CorrelatedRuntimeObservation(observation, resolution, null)));
    }
}
