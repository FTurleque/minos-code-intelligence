package com.minos.storage.local.orchestration;

import com.minos.orchestration.InMemoryIndexStateStore;
import com.minos.orchestration.IndexStateStore;
import com.minos.orchestration.IndexStateStoreException;
import com.minos.orchestration.ProjectIndexState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AUD-QUA-03: a storage failure of the file index state store is an {@link IndexStateStoreException} with its cause
 * kept, and its message never names the storage root; a lease released by another thread stays an invariant
 * violation ({@link IllegalStateException}), for the file store as for the in-memory one.
 */
class FileIndexStateStoreErrorContractTest {

    @TempDir Path temp;

    @Test
    void anUnreadableProjectStateIsAStoreFailureWithoutTheRoot() throws Exception {
        UUID projectId = UUID.randomUUID();
        FileIndexStateStore store = new FileIndexStateStore(temp.resolve("state"));
        Files.write(temp.resolve("state").resolve("projects").resolve(projectId + ".properties"),
                new byte[4 * 1024 * 1024 + 1]);

        IndexStateStoreException failure = assertThrows(IndexStateStoreException.class,
                () -> store.findProjectState(projectId));

        assertNotNull(failure.getCause(), "the original failure is kept");
        assertNoRoot(failure);
    }

    @Test
    void aMissingPropertyIsNamedWithoutAnyPath() throws Exception {
        UUID projectId = UUID.randomUUID();
        FileIndexStateStore store = new FileIndexStateStore(temp.resolve("state"));
        store.saveProjectState(ProjectIndexState.neverIndexed(projectId, Instant.parse("2026-10-21T08:00:00Z")));
        Path file = temp.resolve("state").resolve("projects").resolve(projectId + ".properties");
        Files.writeString(file, Files.readString(file).replaceAll("(?m)^availability=.*$", ""));

        IndexStateStoreException failure = assertThrows(IndexStateStoreException.class,
                () -> store.findProjectState(projectId));

        assertTrue(failure.getMessage().contains("'availability'"), failure.getMessage());
        assertNoRoot(failure);
    }

    @Test
    void anUnparsableProjectStateValueIsAStoreFailureWithoutTheRoot() throws Exception {
        UUID projectId = UUID.randomUUID();
        FileIndexStateStore store = new FileIndexStateStore(temp.resolve("state"));
        store.saveProjectState(ProjectIndexState.neverIndexed(projectId, Instant.parse("2026-10-21T08:00:00Z")));
        Path file = temp.resolve("state").resolve("projects").resolve(projectId + ".properties");
        Files.writeString(file, Files.readString(file).replaceAll("(?m)^availability=.*$", "availability=BOGUS"));

        IndexStateStoreException failure = assertThrows(IndexStateStoreException.class,
                () -> store.findProjectState(projectId));

        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        assertNoRoot(failure);
    }

    @Test
    void anInvalidProjectIdInARunLocatorIsAStoreFailureWithoutTheRoot() throws Exception {
        FileIndexStateStore store = new FileIndexStateStore(temp.resolve("state"));
        UUID runId = UUID.randomUUID();
        Files.writeString(temp.resolve("state").resolve("runs").resolve(".by-id").resolve(runId + ".properties"),
                "projectId=not-a-uuid\n");

        IndexStateStoreException failure = assertThrows(IndexStateStoreException.class, () -> store.findRun(runId));

        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        assertNoRoot(failure);
    }

    @Test
    void anInvalidExecutionCountAndAnUnexpectedFileNameAreStoreFailuresWithoutTheRoot() throws Exception {
        UUID projectId = UUID.randomUUID();
        FileIndexStateStore store = new FileIndexStateStore(temp.resolve("state"));
        Path partition = Files.createDirectories(temp.resolve("state").resolve("runs").resolve(projectId.toString()));
        UUID runId = UUID.randomUUID();
        Files.writeString(partition.resolve(runId + ".properties"),
                "id=" + runId + "\nprojectId=" + projectId + "\nexecution.count=many\n");

        IndexStateStoreException invalidCount = assertThrows(IndexStateStoreException.class,
                () -> store.listRuns(projectId));
        assertNoRoot(invalidCount);

        Files.writeString(partition.resolve(runId + ".properties"),
                "id=" + runId + "\nprojectId=" + projectId + "\nexecution.count=-1\n");
        IndexStateStoreException unsafeCount = assertThrows(IndexStateStoreException.class,
                () -> store.listRuns(projectId));
        assertTrue(unsafeCount.getMessage().contains("unsafe execution count"), unsafeCount.getMessage());
        assertNoRoot(unsafeCount);

        Files.delete(partition.resolve(runId + ".properties"));
        Files.writeString(partition.resolve("not-a-run.properties"), "id=x\n");
        IndexStateStoreException unexpectedName = assertThrows(IndexStateStoreException.class,
                () -> store.listRuns(projectId));
        assertNoRoot(unexpectedName);
    }

    @Test
    void aLeaseReleasedByAnotherThreadIsAnInvariantViolationNotAStoreFailure() throws Exception {
        for (IndexStateStore store : new IndexStateStore[]{
                new FileIndexStateStore(temp.resolve("leases")), new InMemoryIndexStateStore()}) {
            IndexStateStore.ProjectLease lease = store.acquireProjectLease(UUID.randomUUID());
            Throwable foreign = CompletableFuture.supplyAsync(() -> {
                try {
                    lease.close();
                    return null;
                } catch (RuntimeException failure) {
                    return failure;
                }
            }).get();
            assertInstanceOf(IllegalStateException.class, foreign);
            assertFalse(foreign instanceof IndexStateStoreException);
            lease.close();
        }
    }

    private void assertNoRoot(Throwable failure) {
        String root = temp.toAbsolutePath().toString();
        assertFalse(failure.getMessage().contains(root), failure.getMessage());
    }
}
