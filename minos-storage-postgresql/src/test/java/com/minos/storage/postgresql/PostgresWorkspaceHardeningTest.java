package com.minos.storage.postgresql;

import com.minos.orchestration.InMemoryIndexStateStore;
import com.minos.orchestration.IndexStateStore;
import com.minos.registry.ProjectRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresWorkspaceHardeningTest extends PostgresTestSupport {

    @Test
    void concurrentWorkspaceCreationConvergesOnOneDatabaseIdentity() throws Exception {
        ProjectRegistry first = new PostgresProjectRegistry(connections, Files.createTempDirectory("minos-pg-reg-a"));
        ProjectRegistry second = new PostgresProjectRegistry(connections, Files.createTempDirectory("minos-pg-reg-b"));
        CyclicBarrier start = new CyclicBarrier(2);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var left = executor.submit(() -> {
                start.await();
                return first.createWorkspaceWithResult("platform");
            });
            var right = executor.submit(() -> {
                start.await();
                return second.createWorkspaceWithResult("platform");
            });
            var leftResult = left.get();
            var rightResult = right.get();

            assertEquals(leftResult.workspace().id(), rightResult.workspace().id());
            assertEquals(1, (leftResult.createdByThisCall() ? 1 : 0) + (rightResult.createdByThisCall() ? 1 : 0));
            assertEquals(1, first.listWorkspaces().size());
        }
    }

    @Test
    void formerStripeCollisionDoesNotSerializeIndependentProjects() throws Exception {
        UUID firstProject = new UUID(0L, 0L);
        UUID secondProject = new UUID(0L, 256L);
        assertEquals(Math.floorMod(firstProject.hashCode(), 256), Math.floorMod(secondProject.hashCode(), 256));

        var store = new ProjectMutationIndexStateStore(connections, new InMemoryIndexStateStore());
        IndexStateStore.ProjectLease first = store.acquireProjectLease(firstProject);
        try (var executor = Executors.newSingleThreadExecutor()) {
            CountDownLatch acquired = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            var future = executor.submit(() -> {
                try (IndexStateStore.ProjectLease ignored = store.acquireProjectLease(secondProject)) {
                    acquired.countDown();
                    release.await(5, TimeUnit.SECONDS);
                }
                return null;
            });

            boolean independent = acquired.await(1, TimeUnit.SECONDS);
            first.close();
            release.countDown();
            future.get(5, TimeUnit.SECONDS);
            assertTrue(independent, "independent project lease was blocked by local JVM gate collision");
        } finally {
            first.close();
        }
    }

    @Test
    void schemaOwnsWorkspaceNameUniqueness() throws Exception {
        connections.withConnection(connection -> {
            try (var statement = connection.createStatement();
                 var result = statement.executeQuery("""
                         SELECT count(*) FROM pg_indexes
                         WHERE schemaname=current_schema() AND tablename='workspaces'
                           AND indexname='workspaces_name_uq'
                         """)) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
            }
            return null;
        });
    }
}
