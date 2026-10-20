package com.minos.cli.operations;

import com.minos.application.MinosApplication;
import com.minos.incremental.ProjectFingerprint;
import com.minos.incremental.ProjectFingerprintSnapshot;
import com.minos.incremental.ProjectFingerprintSnapshotStore;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The failure paths of an indexing run: a fingerprint that cannot be published, and a provider failure on an
 * incremental run. In both, the retention pass that follows may fail without hiding the original failure.
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

    /** A fingerprint store whose publication is refused; nothing else is reached. */
    private static ProjectFingerprintSnapshotStore refusingFingerprintStore() {
        return new ProjectFingerprintSnapshotStore() {
            @Override public ProjectFingerprintSnapshot publish(UUID projectId, String indexSnapshotId,
                                                                ProjectFingerprint fingerprint) throws IOException {
                throw new IOException("fingerprint publication refused");
            }
            @Override public void promote(UUID projectId, String indexSnapshotId) {
                throw new UnsupportedOperationException("promotion is not reached");
            }
            @Override public Optional<ProjectFingerprintSnapshot> load(UUID projectId, String indexSnapshotId) {
                return Optional.empty();
            }
            @Override public Optional<ProjectFingerprintSnapshot> loadActive(UUID projectId) {
                return Optional.empty();
            }
            @Override public List<String> listIndexSnapshotIds(UUID projectId) {
                return List.of();
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
    void aFingerprintThatCannotBePublishedFailsTheRunAndKeepsARetentionFailureSuppressed() throws Exception {
        IOException retentionFailure = new IOException("retention lock timed out");
        try (MinosApplication application = MinosApplication.builder(temp.resolve("home"))
                .fingerprintStore(refusingFingerprintStore())
                .retentionService((projectId, policy) -> {
                    compactions.incrementAndGet();
                    throw retentionFailure;
                })
                .build()) {
            LocalAutonomousIndexOperations operations =
                    new LocalAutonomousIndexOperations(application, UnaryOperator.identity());

            IOException failure = assertThrows(IOException.class,
                    () -> operations.promoteFingerprint(UUID.randomUUID(), "snapshot-2", null));

            assertEquals("fingerprint publication refused", failure.getMessage());
            assertEquals(1, compactions.get(), "the failed promotion releases what the run retained");
            assertSame(retentionFailure, failure.getSuppressed()[0],
                    "the retention failure is attached to the original failure, never replaces it");
        }
    }

    @Test
    void aSuccessfulRetentionPassAfterAFailureAddsNothingToIt() throws Exception {
        try (MinosApplication application = MinosApplication.builder(temp.resolve("home"))
                .retentionService((projectId, policy) -> {
                    compactions.incrementAndGet();
                    return null;
                })
                .build()) {
            LocalAutonomousIndexOperations operations =
                    new LocalAutonomousIndexOperations(application, UnaryOperator.identity());
            IllegalStateException failure = new IllegalStateException("run failed");

            operations.compactAfterFailure(UUID.randomUUID(), failure);

            assertEquals(1, compactions.get());
            assertEquals(0, failure.getSuppressed().length);
        }
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
