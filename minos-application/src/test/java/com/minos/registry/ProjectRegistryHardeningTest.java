package com.minos.registry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProjectRegistryHardeningTest {

    @Test
    void concurrentInterProcessWorkspaceCreationConvergesOnOneIdentity(@TempDir Path home) throws Exception {
        Path registryRoot = home.resolve("registry");
        ProjectRegistry first = new InterProcessLocalProjectRegistry(registryRoot);
        ProjectRegistry second = new InterProcessLocalProjectRegistry(registryRoot);
        CyclicBarrier start = new CyclicBarrier(2);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<ProjectRegistry.WorkspaceRegistrationResult> left = () -> {
                start.await();
                return first.createWorkspaceWithResult("platform");
            };
            Callable<ProjectRegistry.WorkspaceRegistrationResult> right = () -> {
                start.await();
                return second.createWorkspaceWithResult("platform");
            };
            var leftFuture = executor.submit(left);
            var rightFuture = executor.submit(right);
            var leftResult = leftFuture.get();
            var rightResult = rightFuture.get();

            assertEquals(leftResult.workspace().id(), rightResult.workspace().id());
            assertEquals(1, (leftResult.createdByThisCall() ? 1 : 0) + (rightResult.createdByThisCall() ? 1 : 0));
            assertEquals(1, first.listWorkspaces().size());
        }
    }

    @Test
    void registryNamesAreRejectedBeforeTheyCanExceedTheReadContract(@TempDir Path home) {
        String maximum = "é".repeat(ProjectRegistryLimits.MAX_NAME_UTF8_BYTES / 2);
        String tooLarge = maximum + "a";
        Instant now = Instant.parse("2026-08-15T00:00:00Z");

        new RegisteredWorkspace(UUID.randomUUID(), maximum, List.of(), now, now);
        new RegisteredProject(UUID.randomUUID(), home, maximum, Optional.empty(), now, now);

        assertThrows(IllegalArgumentException.class,
                () -> new RegisteredWorkspace(UUID.randomUUID(), tooLarge, List.of(), now, now));
        assertThrows(IllegalArgumentException.class,
                () -> new RegisteredProject(UUID.randomUUID(), home, tooLarge, Optional.empty(), now, now));
        assertThrows(IllegalArgumentException.class,
                () -> ProjectRegistryLimits.requireName("bad\0name", "name"));
    }
}
