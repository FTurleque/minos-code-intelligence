package com.minos.orchestration;

import com.minos.discovery.ProjectDiscovery;
import com.minos.discovery.ProjectDiscovery.BuildSystem;
import com.minos.discovery.ProjectDiscovery.DiscoveredModule;
import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.discovery.ProjectDiscovery.SourceRoot;
import com.minos.discovery.ProjectDiscovery.SourceRootKind;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;
import com.minos.orchestration.IndexingRuntimePorts.IndexSnapshotStageRequest;
import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexingLifecycleScopedExecutionTest {

    @Test
    void executesNestedProviderScopesAndStagesPortableScopeMetadata(@TempDir Path root) throws Exception {
        Path app = Files.createDirectories(root.resolve("ui/app"));
        Path lib = Files.createDirectories(root.resolve("ui/lib"));
        Path appArtifact = Files.writeString(root.resolve("app.scip"), "app");
        Path libArtifact = Files.writeString(root.resolve("lib.scip"), "lib");

        List<IndexingExecutionRequest> requests = new ArrayList<>();
        IndexerExecutor executor = new IndexerExecutor() {
            @Override
            public String indexerId() {
                return "scip-typescript";
            }

            @Override
            public IndexingArtifact execute(IndexingExecutionRequest request) {
                requests.add(request);
                Path artifact = request.projectRoot().equals(app) ? appArtifact : libArtifact;
                return new IndexingArtifact(
                        Language.TYPESCRIPT,
                        indexerId(),
                        artifact,
                        request.projectRelativeRoot()
                );
            }
        };

        AtomicReference<IndexSnapshotStageRequest> staged = new AtomicReference<>();
        IndexingLifecycleService lifecycle = new IndexingLifecycleService(
                List.of(executor),
                request -> {
                    staged.set(request);
                    return "snapshot-scoped";
                },
                (projectId, runId, snapshotId) -> { },
                new InMemoryIndexStateStore()
        );
        UUID projectId = UUID.randomUUID();
        ProjectDiscovery discovery = new ProjectDiscovery(
                root,
                "polyglot",
                Set.of(Language.TYPESCRIPT),
                Set.of(BuildSystem.NPM),
                List.of(
                        new DiscoveredModule(
                                Path.of("ui/app"),
                                "app",
                                EnumSet.of(BuildSystem.NPM),
                                List.of(new SourceRoot(Path.of("ui/app/src"), SourceRootKind.SOURCE, Language.TYPESCRIPT))
                        ),
                        new DiscoveredModule(
                                Path.of("ui/lib"),
                                "lib",
                                EnumSet.of(BuildSystem.NPM),
                                List.of(new SourceRoot(Path.of("ui/lib/src"), SourceRootKind.SOURCE, Language.TYPESCRIPT))
                        )
                )
        );
        IndexerDescriptor descriptor = new IndexerDescriptor(
                "scip-typescript",
                "0.4.0",
                "scip-typescript",
                Set.of(Language.TYPESCRIPT),
                Set.of(),
                EnumSet.of(IndexerCapability.SYMBOLS, IndexerCapability.REFERENCES, IndexerCapability.MULTI_MODULE),
                IndexerQualification.QUALIFIED,
                100,
                List.of()
        );
        IndexerSelection selection = new IndexerSelection(Language.TYPESCRIPT, descriptor);
        IndexerNegotiationResult negotiation = new IndexerNegotiationResult(
                List.of(selection),
                Set.of(),
                List.of()
        );

        IndexingRun run = lifecycle.execute(projectId, root, discovery, negotiation);

        assertEquals(IndexingRun.Status.SUCCEEDED, run.status());
        assertEquals(2, requests.size());
        assertTrue(requests.stream().allMatch(request -> request.registeredProjectRoot().equals(root)));
        assertEquals(
                List.of(Path.of("ui/app"), Path.of("ui/lib")),
                requests.stream().map(IndexingExecutionRequest::projectRelativeRoot).toList()
        );
        assertEquals(List.of(app, lib), requests.stream().map(IndexingExecutionRequest::projectRoot).toList());
        assertEquals(
                List.of(Path.of("ui/app"), Path.of("ui/lib")),
                staged.get().artifacts().stream().map(IndexingArtifact::projectRelativeRoot).toList()
        );
        assertEquals("snapshot-scoped", run.activeSnapshotAfter().orElseThrow());
    }
}
