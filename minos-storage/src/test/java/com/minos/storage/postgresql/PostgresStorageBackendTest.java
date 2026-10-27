package com.minos.storage.postgresql;

import com.minos.storage.postgresql.project.PostgresStorageRetentionService;
import com.minos.storage.postgresql.project.ProjectMutationIndexStateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PostgresStorageBackendTest extends PostgresTestSupport {

    @Test
    void wiresEveryStoreOnTheSharedPoolOfAMigratedSchema(@TempDir Path home) throws Exception {
        PostgresStorageBackend backend = new PostgresStorageBackend(connections, home);
        UUID project = UUID.randomUUID();

        assertEquals("postgresql", backend.id());
        var created = backend.projectRegistry().createWorkspaceWithResult("platform");
        assertTrue(created.createdByThisCall());
        assertEquals(List.of(created.workspace().id()),
                backend.projectRegistry().listWorkspaces().stream().map(workspace -> workspace.id()).toList());
        assertTrue(backend.snapshotStore().loadActiveKnowledge(project).isEmpty());
        assertTrue(backend.fingerprintStore().loadActive(project).isEmpty());
        assertTrue(backend.semanticVectorStore().load(project.toString()).isEmpty());
        assertTrue(backend.runtimeObservationStore().list(project).isEmpty());
        assertInstanceOf(ProjectMutationIndexStateStore.class, backend.indexStateStore(),
                "index state mutations take the project advisory lock");
        assertInstanceOf(PostgresStorageRetentionService.class, backend.retentionService());
    }

    @Test
    void closeReleasesTheConnectionPool(@TempDir Path home) throws Exception {
        PostgresStorageBackend backend = new PostgresStorageBackend(connections, home);

        backend.close();

        SQLException failure = assertThrows(SQLException.class, () -> connections.withConnection(c -> 1));
        assertEquals("PostgreSQL connection pool is closed", failure.getMessage());
    }
}
