package com.minos.cli.operations;

import com.minos.application.MinosApplication;
import com.minos.orchestration.IndexingResumePolicy;
import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.orchestration.ProjectIndexState;
import com.minos.registry.RegisteredProject;
import com.minos.runtime.ProviderRuntimeManager;
import com.minos.runtime.ProviderRuntimeStatus;
import com.minos.store.CodeKnowledgeSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A provider failure on an incremental run through the command: the retention pass that follows may fail without
 * hiding the original failure. The other failure paths (fingerprint publication, retention after a failure) are
 * proved on the indexing use case of minos-application ({@code ProjectIndexingUseCaseTest}).
 */
class IndexFailurePathsTest {

    @TempDir Path temp;

    private final AtomicInteger executed = new AtomicInteger();
    private final AtomicInteger compactions = new AtomicInteger();

    private ProviderRuntimeManager failingRuntime() {
        ProviderRuntimeStatus ready = new ProviderRuntimeStatus("scip-java", "1.0", ProviderRuntimeStatus.State.READY,
                Optional.empty(), List.of(), true);
        return new ProviderRuntimeManager() {
            @Override public List<ProviderRuntimeStatus> list() { return List.of(ready); }
            @Override public ProviderRuntimeStatus inspect(String providerId) { return ready; }
            @Override public ProviderRuntimeStatus install(String providerId) { return ready; }
            @Override public IndexerExecutor executor(String providerId) {
                return new IndexerExecutor() {
                    @Override public String indexerId() { return providerId; }
                    @Override public IndexingArtifact execute(IndexingExecutionRequest request) {
                        executed.incrementAndGet();
                        throw new IllegalStateException("executor reached");
                    }
                };
            }
        };
    }

    private RegisteredProject indexedProject(MinosApplication application) throws IOException {
        Path root = Files.createDirectories(temp.resolve("project"));
        Files.writeString(root.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion><groupId>demo</groupId><artifactId>demo</artifactId>
                <version>1</version></project>
                """);
        Path source = Files.createDirectories(root.resolve("src/main/java/demo"));
        Files.writeString(source.resolve("Demo.java"), "package demo; public class Demo { }\n");
        RegisteredProject project = application.projectRegistry().registerProject(root, "demo");
        CodeKnowledgeSnapshot snapshot = application.snapshotStore().publish(
                project.id(), "snapshot-1", List.of(), List.of(), List.of());
        application.indexStateStore().saveProjectState(new ProjectIndexState(project.id(),
                ProjectIndexState.Availability.READY, Optional.of(snapshot.snapshotId()), Optional.empty(),
                Instant.parse("2026-09-30T08:00:00Z"), Optional.empty()));
        var fingerprint = application.fingerprintService().capture(project.rootPath());
        application.fingerprintStore().publish(project.id(), snapshot.snapshotId(), fingerprint);
        application.fingerprintStore().promote(project.id(), snapshot.snapshotId());
        return project;
    }

    @Test
    void aProviderFailureOnAnIncrementalRunKeepsAFailingRetentionPassSuppressed() throws Exception {
        IOException retentionFailure = new IOException("retention lock timed out");
        try (MinosApplication application = MinosApplication.builder(temp.resolve("home"))
                .providerRuntimeManager(failingRuntime())
                .retentionService((projectId, policy) -> {
                    compactions.incrementAndGet();
                    throw retentionFailure;
                })
                .build()) {
            RegisteredProject project = indexedProject(application);
            Files.writeString(project.rootPath().resolve("src/main/java/demo/Demo.java"),
                    "package demo; public class Demo { int changed; }\n");
            LocalAutonomousIndexOperations operations =
                    new LocalAutonomousIndexOperations(application, UnaryOperator.identity());

            // Not forced and not complete: the run is the planned one, not the full one.
            String projectId = project.id().toString();
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> operations.execute(
                    projectId, null, false, IndexingResumePolicy.RESUME));

            assertTrue(failure.getMessage().startsWith("indexing run "), failure.getMessage());
            assertEquals(1, executed.get(), "the changed project reached the provider");
            assertTrue(compactions.get() >= 1, "the failed run is compacted");
            assertSame(retentionFailure, failure.getSuppressed()[0]);
        }
    }
}
