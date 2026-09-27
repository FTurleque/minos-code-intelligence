package com.minos.program.analysis;

import com.minos.program.ProgramGraph;
import com.minos.registry.LocalProjectRegistry;
import com.minos.registry.RegisteredProject;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.FileSymbolSnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgramGraphServiceConcurrencyTest {

    @Test
    void differentProjectsCanRunProviderAnalysisConcurrently(@TempDir Path temp) throws Exception {
        LocalProjectRegistry registry = new LocalProjectRegistry(temp.resolve("registry"));
        RegisteredProject first = registry.registerProject(
                Files.createDirectories(temp.resolve("project-a")), "A");
        RegisteredProject second = projectOnDifferentStripe(registry, temp, first);
        FileSymbolSnapshotStore snapshots = new FileSymbolSnapshotStore(temp.resolve("snapshots"));
        snapshots.publish(first.id(), "snapshot-a", List.of(), List.of(), List.of());
        snapshots.publish(second.id(), "snapshot-b", List.of(), List.of(), List.of());

        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        ProgramGraphProvider blocking = new ProgramGraphProvider() {
            @Override public String id() { return "concurrency-fixture"; }

            @Override
            public ProgramGraph analyze(RegisteredProject project, CodeKnowledgeSnapshot snapshot) throws java.io.IOException {
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new java.io.IOException("concurrent provider test timed out");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new java.io.IOException("concurrent provider test interrupted", exception);
                }
                return new ProgramGraph(
                        project.id().toString(), snapshot.snapshotId(), Set.of(), List.of(), List.of(), List.of());
            }
        };
        ProgramGraphService service = new ProgramGraphService(registry, snapshots, List.of(blocking));

        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstFuture = executor.submit(() -> service.getGraph(first.id().toString()));
            var secondFuture = executor.submit(() -> service.getGraph(second.id().toString()));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS),
                        "provider analysis for different projects must not queue behind a global cache lock");
            } finally {
                release.countDown();
            }
            assertEquals(first.id().toString(), firstFuture.get(10, TimeUnit.SECONDS).projectId());
            assertEquals(second.id().toString(), secondFuture.get(10, TimeUnit.SECONDS).projectId());
        }

        assertEquals(2, service.cacheStats().misses());
    }

    private static RegisteredProject projectOnDifferentStripe(
            LocalProjectRegistry registry,
            Path temp,
            RegisteredProject first
    ) throws Exception {
        int firstStripe = stripe(first);
        for (int index = 0; index < 128; index++) {
            RegisteredProject candidate = registry.registerProject(
                    Files.createDirectories(temp.resolve("project-b-" + index)), "B" + index);
            if (stripe(candidate) != firstStripe) return candidate;
        }
        throw new AssertionError("unable to allocate project ids on different build-lock stripes");
    }

    private static int stripe(RegisteredProject project) {
        return Math.floorMod(project.id().toString().hashCode(), 64);
    }
}
