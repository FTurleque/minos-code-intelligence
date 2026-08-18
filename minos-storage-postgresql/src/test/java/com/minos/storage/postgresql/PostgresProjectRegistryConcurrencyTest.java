package com.minos.storage.postgresql;

import com.minos.registry.RegisteredProject;
import com.minos.registry.RegisteredWorkspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresProjectRegistryConcurrencyTest extends PostgresTestSupport {

    @Test
    void assignmentCannotResurrectProjectDeletedByConcurrentTransaction(@TempDir Path temp) throws Exception {
        Path home = Files.createDirectories(temp.resolve("home"));
        PostgresProjectRegistry registry = new PostgresProjectRegistry(connections, home);
        RegisteredProject project = registry.registerProject(
                Files.createDirectories(temp.resolve("project")), "Project");
        RegisteredWorkspace workspace = registry.createWorkspace("Workspace");

        CountDownLatch deleteIssued = new CountDownLatch(1);
        CountDownLatch allowDeleteCommit = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> deletion = executor.submit(() -> connections.inTransaction(connection -> {
                PostgresProjectMutationLock.acquire(connection, project.id());
                try (var statement = connection.prepareStatement("DELETE FROM projects WHERE id=?")) {
                    statement.setObject(1, project.id());
                    statement.executeUpdate();
                }
                deleteIssued.countDown();
                await(allowDeleteCommit, Duration.ofSeconds(5));
                return null;
            }));

            assertTrue(deleteIssued.await(5, TimeUnit.SECONDS), "concurrent delete must reach its uncommitted state");
            Future<?> assignment = executor.submit(
                    () -> registry.assignProjectToWorkspace(project.id(), workspace.id()));

            allowDeleteCommit.countDown();
            deletion.get(5, TimeUnit.SECONDS);
            ExecutionException failure = org.junit.jupiter.api.Assertions.assertThrows(
                    ExecutionException.class,
                    () -> assignment.get(5, TimeUnit.SECONDS));
            assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        }

        assertTrue(registry.findProject(project.id()).isEmpty(),
                "a workspace assignment must never recreate a project deleted concurrently");
    }

    private static void await(CountDownLatch latch, Duration timeout) throws IOException {
        try {
            if (!latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IOException("timed out while coordinating PostgreSQL registry concurrency test");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while coordinating PostgreSQL registry concurrency test", exception);
        }
    }
}
