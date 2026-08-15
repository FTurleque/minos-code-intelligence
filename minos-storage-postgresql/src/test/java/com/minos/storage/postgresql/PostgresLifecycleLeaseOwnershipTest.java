package com.minos.storage.postgresql;

import com.minos.orchestration.InMemoryIndexStateStore;
import com.minos.orchestration.IndexStateStore;
import com.minos.orchestration.ProjectIndexState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresLifecycleLeaseOwnershipTest extends PostgresTestSupport {

    @Test
    void wrongThreadCloseDoesNotPoisonLifecycleLease() throws Exception {
        ProjectMutationIndexStateStore store = new ProjectMutationIndexStateStore(
                connections, new InMemoryIndexStateStore());
        UUID projectId = UUID.randomUUID();
        IndexStateStore.ProjectLease lease = store.acquireProjectLease(projectId);

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = Thread.ofPlatform().start(() -> {
            try {
                lease.close();
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        });
        thread.join();
        assertInstanceOf(IllegalStateException.class, failure.get());

        lease.close();
        lease.close();
        try (IndexStateStore.ProjectLease reacquired = store.acquireProjectLease(projectId)) {
            assertNotNull(reacquired);
        }
    }

    @Test
    void lostLifecycleSessionCannotContinueMutatingThroughAnotherConnection() throws Exception {
        UUID projectId = UUID.randomUUID();
        PostgresJsonCodec json = new PostgresJsonCodec();
        ProjectMutationIndexStateStore first = new ProjectMutationIndexStateStore(
                connections, new PostgresIndexStateStore(connections, json));
        IndexStateStore.ProjectLease firstLease = first.acquireProjectLease(projectId);

        int lifecycleBackendPid = connections.withConnection(connection -> {
            try (var statement = connection.prepareStatement("SELECT pg_backend_pid()");
                 var result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        });

        PostgresConnectionFactory competingConnections = createFactory("minos");
        try {
            boolean terminated = competingConnections.withConnection(connection -> {
                try (var statement = connection.prepareStatement("SELECT pg_terminate_backend(?)")) {
                    statement.setInt(1, lifecycleBackendPid);
                    try (var result = statement.executeQuery()) {
                        assertTrue(result.next());
                        return result.getBoolean(1);
                    }
                }
            });
            assertTrue(terminated, "fixture must terminate the session that owns the lifecycle advisory lock");

            ProjectMutationIndexStateStore competitor = new ProjectMutationIndexStateStore(
                    competingConnections, new PostgresIndexStateStore(competingConnections, json));
            AtomicReference<Throwable> competitorFailure = new AtomicReference<>();
            Thread competitorThread = Thread.ofPlatform().start(() -> {
                try (IndexStateStore.ProjectLease ignored = competitor.acquireProjectLease(projectId)) {
                    competitor.saveProjectState(state(
                            projectId, ProjectIndexState.Availability.READY,
                            Optional.of("snapshot-competitor"), "competitor owns the recovered lease"));
                } catch (Throwable thrown) {
                    competitorFailure.set(thrown);
                }
            });
            competitorThread.join();
            if (competitorFailure.get() != null) {
                throw new AssertionError("competitor must acquire the server-side lease after session loss",
                        competitorFailure.get());
            }

            assertThrows(IllegalStateException.class, () -> first.saveProjectState(state(
                    projectId, ProjectIndexState.Availability.FAILED,
                    Optional.of("snapshot-stale-writer"), "stale writer must not persist")));

            ProjectIndexState authoritative = new PostgresIndexStateStore(competingConnections, json)
                    .findProjectState(projectId).orElseThrow();
            assertEquals(ProjectIndexState.Availability.READY, authoritative.availability());
            assertEquals(Optional.of("snapshot-competitor"), authoritative.activeSnapshotId());
            assertEquals(Optional.of("competitor owns the recovered lease"), authoritative.detail());
        } finally {
            competingConnections.close();
        }

        assertThrows(IllegalStateException.class, firstLease::close,
                "releasing a lifecycle lease whose session died must report lost ownership");
    }

    private static ProjectIndexState state(
            UUID projectId,
            ProjectIndexState.Availability availability,
            Optional<String> activeSnapshot,
            String detail
    ) {
        return new ProjectIndexState(
                projectId,
                availability,
                activeSnapshot,
                Optional.empty(),
                Instant.parse("2026-08-15T12:00:00Z"),
                Optional.of(detail));
    }
}
