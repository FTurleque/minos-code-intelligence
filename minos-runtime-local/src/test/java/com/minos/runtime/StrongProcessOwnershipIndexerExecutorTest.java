package com.minos.runtime;

import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;
import com.minos.orchestration.IndexerQualification;
import com.minos.orchestration.IndexingMode;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StrongProcessOwnershipIndexerExecutorTest {

    @TempDir
    Path temporary;

    @Test
    void unavailableCapabilityFailsClosedBeforeBuildingOrStartingProviderPlan() {
        AtomicBoolean planBuilt = new AtomicBoolean();
        Path project = temporary.resolve("unavailable-project");
        ProcessIndexerExecutor delegate = new ProcessIndexerExecutor(
                "fake-provider",
                temporary.resolve("unavailable-home"),
                (request, runDirectory) -> {
                    planBuilt.set(true);
                    throw new AssertionError("plan must not be built without strong ownership");
                });
        StrongProcessOwnershipIndexerExecutor executor = new StrongProcessOwnershipIndexerExecutor(
                delegate,
                new StrongProcessOwnershipIndexerExecutor.BoundaryProvider() {
                    @Override
                    public StrongProcessOwnershipIndexerExecutor.Capability capability() {
                        return StrongProcessOwnershipIndexerExecutor.Capability.unavailable(
                                "fixture", "kernel ownership unavailable");
                    }

                    @Override
                    public ProcessIndexerExecutor.ProcessPlanTransformer transformer(IndexingExecutionRequest request) {
                        throw new AssertionError("transformer must not be requested without capability");
                    }
                });

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> executor.execute(request(project)));

        assertFalse(planBuilt.get());
        assertTrue(failure.getMessage().contains("required but unavailable"));
    }

    @Test
    void timeoutAlwaysKillsAndReleasesStrongBoundary() throws Exception {
        Path project = temporary.resolve("timeout-project");
        Files.createDirectories(project);
        Path source = temporary.resolve("TimeoutProvider.java");
        Files.writeString(source, """
                public class TimeoutProvider {
                    public static void main(String[] args) throws Exception {
                        Thread.sleep(300_000L);
                    }
                }
                """);
        AtomicBoolean killed = new AtomicBoolean();
        AtomicBoolean released = new AtomicBoolean();
        ProcessIndexerExecutor delegate = new ProcessIndexerExecutor(
                "fake-provider",
                temporary.resolve("timeout-home"),
                (request, runDirectory) -> new IndexerProcessPlan(
                        List.of(javaExecutable(), source.toString()),
                        project,
                        Map.of(),
                        runDirectory.resolve("index.scip"),
                        Duration.ofMillis(200)));
        StrongProcessOwnershipIndexerExecutor executor = new StrongProcessOwnershipIndexerExecutor(
                delegate,
                strongFixtureBoundary(killed, released, false));

        assertThrows(IllegalStateException.class, () -> executor.execute(request(project)));
        assertTrue(killed.get(), "timeout cleanup must kill the OS boundary");
        assertTrue(released.get(), "timeout cleanup must reclaim the OS boundary");
    }

    @Test
    void containmentReleaseExceptionIsNotSilentlyIgnoredAfterProviderCleanup() throws Exception {
        Path project = temporary.resolve("release-project");
        Files.createDirectories(project);
        Path source = temporary.resolve("SuccessfulProvider.java");
        Files.writeString(source, """
                import java.nio.file.*;
                public class SuccessfulProvider {
                    public static void main(String[] args) throws Exception {
                        Files.writeString(Path.of(args[0]), "artifact");
                    }
                }
                """);
        AtomicBoolean killed = new AtomicBoolean();
        AtomicBoolean released = new AtomicBoolean();
        ProcessIndexerExecutor delegate = new ProcessIndexerExecutor(
                "fake-provider",
                temporary.resolve("release-home"),
                (request, runDirectory) -> new IndexerProcessPlan(
                        List.of(javaExecutable(), source.toString(), runDirectory.resolve("generated.scip").toString()),
                        project,
                        Map.of(),
                        runDirectory.resolve("generated.scip"),
                        Duration.ofSeconds(30)));
        StrongProcessOwnershipIndexerExecutor executor = new StrongProcessOwnershipIndexerExecutor(
                delegate,
                strongFixtureBoundary(killed, released, true));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> executor.execute(request(project)));

        assertTrue(killed.get(), "successful provider completion must still terminate remaining job members");
        assertTrue(released.get(), "release must be attempted on every exit path");
        assertTrue(failure.getMessage().contains("fixture release failure"));
    }

    private static StrongProcessOwnershipIndexerExecutor.BoundaryProvider strongFixtureBoundary(
            AtomicBoolean killed,
            AtomicBoolean released,
            boolean failRelease
    ) {
        return new StrongProcessOwnershipIndexerExecutor.BoundaryProvider() {
            @Override
            public StrongProcessOwnershipIndexerExecutor.Capability capability() {
                return StrongProcessOwnershipIndexerExecutor.Capability.available("fixture-strong-boundary");
            }

            @Override
            public ProcessIndexerExecutor.ProcessPlanTransformer transformer(IndexingExecutionRequest request) {
                return new ProcessIndexerExecutor.ProcessPlanTransformer() {
                    @Override
                    public IndexerProcessPlan transform(IndexerProcessPlan plan, Path runDirectory) {
                        return plan;
                    }

                    @Override
                    public void killContainedJob() {
                        killed.set(true);
                    }

                    @Override
                    public void releaseContainment() {
                        released.set(true);
                        if (failRelease) throw new IllegalStateException("fixture release failure");
                    }
                };
            }
        };
    }

    private static IndexingExecutionRequest request(Path project) {
        try {
            Files.createDirectories(project);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
        IndexerDescriptor descriptor = new IndexerDescriptor(
                "fake-provider", "1", "fake", Set.of(Language.JAVA), Set.of(), Set.of(),
                IndexerQualification.QUALIFIED, 1, List.of());
        return new IndexingExecutionRequest(
                UUID.randomUUID(), UUID.randomUUID(), project,
                new IndexerSelection(Language.JAVA, descriptor), IndexingMode.FULL, List.of());
    }

    private static String javaExecutable() {
        return Path.of(
                System.getProperty("java.home"),
                "bin",
                CommandLocator.isWindows() ? "java.exe" : "java").toString();
    }
}
