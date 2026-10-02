package com.minos.cli;

import com.minos.application.MinosApplication;
import com.minos.orchestration.IndexingResumePolicy;
import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
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
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S6: the retention lock is bounded, so a compaction can now time out. Retention is maintenance run
 * after an index; when it cannot run, an index that succeeded stays a success and the CLI says so
 * without naming a path, instead of turning the whole command into a failure.
 */
class RetentionFailureAfterIndexTest {

    @TempDir Path temp;

    private static ProviderRuntimeManager readyRuntime() {
        ProviderRuntimeStatus ready = new ProviderRuntimeStatus("scip-java", "1.0", ProviderRuntimeStatus.State.READY,
                Optional.empty(), List.of(), true);
        return new ProviderRuntimeManager() {
            @Override public List<ProviderRuntimeStatus> list() { return List.of(ready); }
            @Override public ProviderRuntimeStatus inspect(String providerId) { return ready; }
            @Override public ProviderRuntimeStatus install(String providerId) { return ready; }
            @Override public IndexerExecutor executor(String providerId) {
                throw new IllegalStateException("no provider is reached when nothing changed");
            }
        };
    }

    private RegisteredProject indexedAndUnchanged(MinosApplication application) throws IOException {
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
    void aRetentionTimeoutAfterAnUnchangedIndexIsReportedNotRaised() throws Exception {
        Path home = temp.resolve("home");
        String secretPath = temp.toString();
        try (MinosApplication application = MinosApplication.builder(home)
                .providerRuntimeManager(readyRuntime())
                .retentionService((projectId, policy) -> {
                    throw new IOException("timed out waiting for storage retention lock cross-process lease after PT10S "
                            + secretPath);
                })
                .build()) {
            RegisteredProject project = indexedAndUnchanged(application);
            LocalAutonomousIndexOperations operations =
                    new LocalAutonomousIndexOperations(application, UnaryOperator.identity());

            AutonomousIndexOperations.IndexExecutionView view = operations.execute(
                    project.id().toString(), null, false, IndexingResumePolicy.RESUME);

            assertEquals("NO_CHANGES", view.status());
            assertNotNull(view.diagnostic(), "the skipped retention is reported");
            assertTrue(view.diagnostic().contains("retention"), view.diagnostic());
            assertFalse(view.diagnostic().contains(secretPath), "no path in the diagnostic: " + view.diagnostic());
        }
    }
}
