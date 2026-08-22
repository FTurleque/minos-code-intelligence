package com.minos.registry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterProcessLocalProjectRegistryTest {

    @TempDir
    Path temporary;

    @Test
    void concurrentRegistryInstancesPreserveOneRootIdentity() throws Exception {
        Path storage = temporary.resolve("registry");
        Path project = Files.createDirectory(temporary.resolve("project"));
        InterProcessLocalProjectRegistry first = new InterProcessLocalProjectRegistry(storage);
        InterProcessLocalProjectRegistry second = new InterProcessLocalProjectRegistry(storage);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> {
                start.await();
                return first.registerProject(project, "first");
            });
            var two = executor.submit(() -> {
                start.await();
                return second.registerProject(project, "second");
            });
            start.countDown();
            RegisteredProject left = one.get();
            RegisteredProject right = two.get();
            assertEquals(left.id(), right.id());
        }
        assertEquals(1, first.listProjects().size());
        assertTrue(first.deleteProject(first.listProjects().getFirst().id()));
        assertTrue(first.listProjects().isEmpty());
    }

    @Test
    void concurrentRegistrationResultGrantsRollbackOwnershipToExactlyOneCaller() throws Exception {
        Path storage = temporary.resolve("registration-result");
        Path project = Files.createDirectory(temporary.resolve("registration-result-project"));
        InterProcessLocalProjectRegistry first = new InterProcessLocalProjectRegistry(storage);
        InterProcessLocalProjectRegistry second = new InterProcessLocalProjectRegistry(storage);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> {
                start.await();
                return first.registerProjectWithResult(project, "first");
            });
            var two = executor.submit(() -> {
                start.await();
                return second.registerProjectWithResult(project, "second");
            });
            start.countDown();
            ProjectRegistry.RegistrationResult left = one.get();
            ProjectRegistry.RegistrationResult right = two.get();
            assertEquals(left.project().id(), right.project().id());
            assertTrue(left.createdByThisCall() ^ right.createdByThisCall());
            assertFalse(left.createdByThisCall() && right.createdByThisCall());
        }
    }
}
