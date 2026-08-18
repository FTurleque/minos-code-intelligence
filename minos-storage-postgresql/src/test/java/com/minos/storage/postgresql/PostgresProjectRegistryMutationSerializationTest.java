package com.minos.storage.postgresql;

import com.minos.registry.RegisteredProject;
import com.minos.registry.RegisteredWorkspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresProjectRegistryMutationSerializationTest extends PostgresTestSupport {

    @Test
    void workspaceMutationAndDeleteWaitForTheSameProjectAdvisoryLock(@TempDir Path temp) throws Exception {
        PostgresProjectRegistry registry = new PostgresProjectRegistry(
                connections,
                Files.createDirectories(temp.resolve("home")));
        RegisteredProject project = registry.registerProject(
                Files.createDirectories(temp.resolve("project")),
                "Project");
        RegisteredWorkspace workspace = registry.createWorkspace("Workspace");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            LockHold assignHold = holdProjectLock(executor, project);
            Future<RegisteredProject> assignment = executor.submit(
                    () -> registry.assignProjectToWorkspace(project.id(), workspace.id()));
            assertBlocked(assignment);
            assignHold.release().countDown();
            assignHold.holder().get(5, TimeUnit.SECONDS);
            assertEquals(workspace.id(), assignment.get(5, TimeUnit.SECONDS).workspaceId().orElseThrow());

            LockHold deleteHold = holdProjectLock(executor, project);
            Future<Boolean> deletion = executor.submit(() -> registry.deleteProject(project.id()));
            assertBlocked(deletion);
            deleteHold.release().countDown();
            deleteHold.holder().get(5, TimeUnit.SECONDS);
            assertTrue(deletion.get(5, TimeUnit.SECONDS));
            assertTrue(registry.findProject(project.id()).isEmpty(),
                    "serialized workspace mutation must never resurrect a deleted project");
        } finally {
            executor.shutdownNow();
        }
    }

    private LockHold holdProjectLock(ExecutorService executor, RegisteredProject project) throws Exception {
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<Void> holder = executor.submit(() -> {
            connections.inTransaction(connection -> {
                PostgresProjectMutationLock.acquire(connection, project.id());
                acquired.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IOException("timed out while holding PostgreSQL project mutation lock");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted while holding PostgreSQL project mutation lock", exception);
                }
                return null;
            });
            return null;
        });
        assertTrue(acquired.await(5, TimeUnit.SECONDS));
        return new LockHold(holder, release);
    }

    private static void assertBlocked(Future<?> future) {
        assertThrows(TimeoutException.class, () -> future.get(250, TimeUnit.MILLISECONDS),
                "project mutation must block behind the shared PostgreSQL advisory lock");
    }

    private record LockHold(Future<Void> holder, CountDownLatch release) {
    }
}
