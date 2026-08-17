package com.minos.orchestration;

import com.minos.discovery.ProjectDiscovery;
import com.minos.discovery.ProjectDiscovery.BuildSystem;
import com.minos.discovery.ProjectDiscovery.DiscoveredModule;
import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.discovery.ProjectDiscovery.SourceRoot;
import com.minos.discovery.ProjectDiscovery.SourceRootKind;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;
import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs(OS.LINUX)
class IndexingExecutionPathContainmentTest {

    @Test
    void internalSymlinkScopeResolvesToCanonicalDirectory(@TempDir Path temporary) throws Exception {
        Path project = Files.createDirectory(temporary.resolve("project"));
        Path realApp = Files.createDirectory(project.resolve("real-app"));
        Files.createSymbolicLink(project.resolve("app"), Path.of("real-app"));
        AtomicReference<IndexingExecutionRequest> executed = new AtomicReference<>();

        IndexingRun run = execute(project, executed, new AtomicBoolean());

        assertEquals(IndexingRun.Status.SUCCEEDED, run.status(), run.message().orElse("<no run message>"));
        assertEquals(project.toRealPath(), executed.get().registeredProjectRoot());
        assertEquals(project.toRealPath().resolve("app"), executed.get().projectRoot());
        assertEquals(realApp.toRealPath(), executed.get().projectRoot().toRealPath());
        assertEquals(Path.of("app"), executed.get().projectRelativeRoot());
    }

    @Test
    void internalSymlinkChainResolvesToCanonicalDirectory(@TempDir Path temporary) throws Exception {
        Path project = Files.createDirectory(temporary.resolve("project"));
        Path realApp = Files.createDirectory(project.resolve("real-app"));
        Files.createSymbolicLink(project.resolve("middle"), Path.of("real-app"));
        Files.createSymbolicLink(project.resolve("app"), Path.of("middle"));
        AtomicReference<IndexingExecutionRequest> executed = new AtomicReference<>();

        IndexingRun run = execute(project, executed, new AtomicBoolean());

        assertEquals(IndexingRun.Status.SUCCEEDED, run.status(), run.message().orElse("<no run message>"));
        assertEquals(project.toRealPath().resolve("app"), executed.get().projectRoot());
        assertEquals(realApp.toRealPath(), executed.get().projectRoot().toRealPath());
    }

    @Test
    void symlinkScopeResolvingOutsideProjectFailsClosedBeforeProviderExecution(@TempDir Path temporary)
            throws Exception {
        Path project = Files.createDirectory(temporary.resolve("project"));
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Files.createSymbolicLink(project.resolve("app"), outside);
        AtomicReference<IndexingExecutionRequest> executed = new AtomicReference<>();
        AtomicBoolean staged = new AtomicBoolean();

        IndexingRun run = execute(project, executed, staged);

        assertEquals(IndexingRun.Status.FAILED, run.status());
        assertTrue(run.message().orElseThrow().contains("resolves outside project"));
        assertNull(executed.get());
        assertFalse(staged.get(), "an escaped scope must fail before snapshot staging");
    }

    @Test
    void brokenSymlinkScopeFailsClosedBeforeProviderExecution(@TempDir Path temporary) throws Exception {
        Path project = Files.createDirectory(temporary.resolve("project"));
        Files.createSymbolicLink(project.resolve("app"), Path.of("missing"));
        AtomicReference<IndexingExecutionRequest> executed = new AtomicReference<>();
        AtomicBoolean staged = new AtomicBoolean();

        IndexingRun run = execute(project, executed, staged);

        assertEquals(IndexingRun.Status.FAILED, run.status());
        assertTrue(run.message().orElseThrow().contains("missing or outside project"));
        assertNull(executed.get());
        assertFalse(staged.get(), "a broken scope must fail before snapshot staging");
    }

    private static IndexingRun execute(
            Path project,
            AtomicReference<IndexingExecutionRequest> executed,
            AtomicBoolean staged
    ) throws Exception {
        Path artifact = Files.writeString(project.resolve("index.scip"), "scip");
        IndexerExecutor executor = new IndexerExecutor() {
            @Override
            public String indexerId() {
                return "scip-typescript";
            }

            @Override
            public IndexingArtifact execute(IndexingExecutionRequest request) {
                executed.set(request);
                return new IndexingArtifact(
                        Language.TYPESCRIPT,
                        indexerId(),
                        artifact,
                        request.projectRelativeRoot());
            }
        };
        IndexingLifecycleService lifecycle = new IndexingLifecycleService(
                List.of(executor),
                request -> {
                    staged.set(true);
                    return "snapshot-contained";
                },
                (projectId, runId, snapshotId) -> { },
                new InMemoryIndexStateStore());

        return lifecycle.execute(UUID.randomUUID(), project, discovery(project), negotiation());
    }

    private static ProjectDiscovery discovery(Path project) {
        return new ProjectDiscovery(
                project,
                "symlink-scope",
                Set.of(Language.TYPESCRIPT),
                Set.of(BuildSystem.NPM),
                List.of(new DiscoveredModule(
                        Path.of("app"),
                        "app",
                        EnumSet.of(BuildSystem.NPM),
                        List.of(new SourceRoot(Path.of("app/src"), SourceRootKind.SOURCE, Language.TYPESCRIPT)))));
    }

    private static IndexerNegotiationResult negotiation() {
        IndexerDescriptor descriptor = new IndexerDescriptor(
                "scip-typescript",
                "0.4.0",
                "scip-typescript",
                Set.of(Language.TYPESCRIPT),
                Set.of(),
                EnumSet.of(IndexerCapability.SYMBOLS, IndexerCapability.REFERENCES),
                IndexerQualification.QUALIFIED,
                100,
                List.of());
        return new IndexerNegotiationResult(
                List.of(new IndexerSelection(Language.TYPESCRIPT, descriptor)),
                Set.of(),
                List.of());
    }
}
