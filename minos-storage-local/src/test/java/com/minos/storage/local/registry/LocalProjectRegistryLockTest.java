package com.minos.storage.local.registry;

import com.minos.registry.RegisteredProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The registry serialises its operations on a lock of its own: code that synchronises on a registry instance it was
 * handed (SpotBugs USO_UNSAFE_METHOD_SYNCHRONIZATION) cannot stall the operations of every other caller.
 */
class LocalProjectRegistryLockTest {

    @Test
    void anOutsideMonitorOnTheRegistryDoesNotBlockItsOperations(@TempDir Path temp) throws Exception {
        LocalProjectRegistry registry = new LocalProjectRegistry(temp.resolve("registry"));
        Path root = Files.createDirectories(temp.resolve("project"));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            synchronized (registry) {
                Future<RegisteredProject> registered = executor.submit(() -> registry.registerProject(root, "Demo"));
                RegisteredProject project = registered.get(30, TimeUnit.SECONDS);
                Future<List<RegisteredProject>> listed = executor.submit(registry::listProjects);
                assertEquals(List.of(project), listed.get(30, TimeUnit.SECONDS));
            }
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(30, TimeUnit.SECONDS);
        }
    }
}
