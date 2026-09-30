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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Q20 : {@code --no-resume} force un run COMPLET, comme l'annoncent l'aide et l'ADR 0039 §6
 * (« {@code --no-resume} force un run complet »). Avant, le drapeau ne pilotait que la politique de
 * reprise : sur un projet déjà indexé et inchangé, {@code index --no-resume} répondait {@code NO_CHANGES}
 * sans rien exécuter.
 */
class NoResumeIndexingTest {

    @TempDir Path temp;

    private final AtomicInteger executed = new AtomicInteger();

    private ProviderRuntimeManager readyRuntime() {
        ProviderRuntimeStatus ready = new ProviderRuntimeStatus("scip-java", "1.0", ProviderRuntimeStatus.State.READY,
                Optional.empty(), List.of(), true);
        return new ProviderRuntimeManager() {
            @Override public List<ProviderRuntimeStatus> list() { return List.of(ready); }
            @Override public ProviderRuntimeStatus inspect(String providerId) { return ready; }
            @Override public ProviderRuntimeStatus install(String providerId) { return ready; }
            @Override public IndexerExecutor executor(String providerId) {
                return new IndexerExecutor() {
                    @Override public String indexerId() { return providerId; }
                    @Override public com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact execute(
                            com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest request) {
                        executed.incrementAndGet();
                        throw new IllegalStateException("executor reached");
                    }
                };
            }
        };
    }

    /** Un projet Java déjà indexé et inchangé depuis : son plan dit qu'il n'y a rien à indexer. */
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
    void noResumeRunsCompletelyWhereTheDefaultPolicySaysNothingChanged() throws Exception {
        try (MinosApplication application = MinosApplication.builder(temp.resolve("home"))
                .providerRuntimeManager(readyRuntime()).build()) {
            RegisteredProject project = indexedAndUnchanged(application);
            LocalAutonomousIndexOperations operations =
                    new LocalAutonomousIndexOperations(application, UnaryOperator.identity());

            // Control: with the default policy an unchanged project is not indexed again.
            AutonomousIndexOperations.IndexExecutionView untouched = operations.execute(
                    project.id().toString(), null, false, IndexingResumePolicy.RESUME);
            assertEquals("NO_CHANGES", untouched.status());
            assertEquals(0, executed.get());

            // --no-resume: do not reopen anything and run fully -- the provider is executed.
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> operations.execute(
                    project.id().toString(), null, false, IndexingResumePolicy.NO_RESUME));
            assertTrue(failure.getMessage().contains("failed") || failure.getMessage().contains("executor reached"),
                    failure.getMessage());
            assertEquals(1, executed.get(), "--no-resume must execute the providers fully, not answer NO_CHANGES");
        }
    }

    @Test
    void resumeOnlyStillRefusesAnUnchangedProject() throws Exception {
        try (MinosApplication application = MinosApplication.builder(temp.resolve("home"))
                .providerRuntimeManager(readyRuntime()).build()) {
            RegisteredProject project = indexedAndUnchanged(application);
            LocalAutonomousIndexOperations operations =
                    new LocalAutonomousIndexOperations(application, UnaryOperator.identity());

            IllegalStateException refusal = assertThrows(IllegalStateException.class, () -> operations.execute(
                    project.id().toString(), null, false, IndexingResumePolicy.RESUME_ONLY));
            assertTrue(refusal.getMessage().contains("resume-only indexing refused"), refusal.getMessage());
            assertEquals(0, executed.get());
        }
    }
}
