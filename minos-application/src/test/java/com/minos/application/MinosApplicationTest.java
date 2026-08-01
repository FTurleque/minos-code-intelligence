package com.minos.application;

import com.minos.domain.InformationNature;
import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.PositionEncoding;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.domain.SymbolLocation;
import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotPromoter;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotStager;
import com.minos.program.ProgramGraph;
import com.minos.program.ProgramGraphCapability;
import com.minos.program.analysis.FingerprintConstrainedJavaProgramGraphProvider;
import com.minos.program.analysis.JavaSourceProgramGraphProvider;
import com.minos.registry.RegisteredProject;
import com.minos.runtime.ProviderRuntimeManager;
import com.minos.runtime.ProviderRuntimeStatus;
import com.minos.store.FileRuntimeObservationStore;
import com.minos.store.FileSymbolSnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinosApplicationTest {

    @Test
    void opensOneStableCompositionForOneHome(@TempDir Path root) throws Exception {
        Path requestedHome = root.resolve("nested").resolve("..").resolve("minos-home");

        MinosApplication application = MinosApplication.open(requestedHome);

        assertEquals(requestedHome.toAbsolutePath().normalize(), application.home());
        assertSame(application.projectRegistry(), application.projectRegistry());
        assertSame(application.snapshotStore(), application.snapshotStore());
        assertSame(application.indexStateStore(), application.indexStateStore());
        assertSame(application.fingerprintStore(), application.fingerprintStore());
        assertSame(application.discoveryService(), application.discoveryService());
        assertSame(application.architectureQuery(), application.architectureQuery());
        assertSame(application.impactQuery(), application.impactQuery());
        assertSame(application.workspaceIntelligence(), application.workspaceIntelligence());
        assertSame(application.runtimeObservationStore(), application.runtimeObservationStore());
        assertSame(application.runtimeIntelligenceService(), application.runtimeIntelligenceService());
        assertSame(application.providerRuntimeManager(), application.providerRuntimeManager());
        assertSame(application.snapshotStager(), application.snapshotStager());
        assertSame(application.snapshotPromoter(), application.snapshotPromoter());
        assertSame(application.gitIntelligence(), application.gitIntelligence());
        assertFalse(application.hostedControlPlaneService().isPresent());
        Set<String> providerIds = application.indexerDescriptors().stream()
                .map(value -> value.id())
                .collect(Collectors.toUnmodifiableSet());
        assertEquals(Set.of(
                "scip-java",
                "scip-typescript",
                "scip-python",
                "scip-clang",
                "scip-dotnet",
                "scip-go",
                "rust-analyzer-scip"
        ), providerIds);
        assertTrue(application.programGraphService().providerIds()
                .contains(JavaSourceProgramGraphProvider.PROVIDER_ID));
    }

    @Test
    void productionCompositionExposesM22CapabilitiesFromOpen(@TempDir Path root) throws Exception {
        Path projectRoot = Files.createDirectories(root.resolve("project"));
        String fileId = "src/main/java/demo/VerticalFixture.java";
        Path source = projectRoot.resolve(fileId);
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package demo;
                final class VerticalFixture {
                    int run(int value) {
                        int copy = value;
                        return copy;
                    }
                }
                """, StandardCharsets.UTF_8);

        Path home = root.resolve("minos-home");
        String snapshotId = "snapshot-p0-vertical";
        MinosApplication application = MinosApplication.open(home);
        RegisteredProject project = application.projectRegistry()
                .registerProject(projectRoot, "vertical-fixture");
        application.snapshotStore().publish(
                project.id(),
                snapshotId,
                List.of(javaSymbol(project, fileId)),
                List.of(),
                List.of());
        application.fingerprintStore().publish(
                project.id(),
                snapshotId,
                application.fingerprintService().capture(projectRoot));
        application.fingerprintStore().promote(project.id(), snapshotId);

        ProgramGraph graph = application.programGraphService().getGraph(project.id().toString());
        ProgramGraph warm = application.programGraphService().getGraph(project.id().toString());

        assertSame(graph, warm);
        assertTrue(graph.supports(ProgramGraphCapability.CONTROL_FLOW));
        assertTrue(graph.supports(ProgramGraphCapability.LOCAL_DATA_FLOW));
        assertTrue(graph.edges().stream()
                .filter(edge -> edge.nature() == InformationNature.DERIVED)
                .anyMatch(edge -> JavaSourceProgramGraphProvider.PROVIDER_ID
                        .equals(edge.origin().providerId())));
        assertFalse(graph.limitations().contains("CONTROL_FLOW_UNAVAILABLE"));
        assertFalse(graph.limitations().contains("LOCAL_DATA_FLOW_UNAVAILABLE"));
        assertEquals(1, application.programGraphService().cacheStats().misses());
        assertEquals(1, application.programGraphService().cacheStats().hits());

        Files.writeString(source, """
                package demo;
                final class VerticalFixture {
                    int changed() { return 42; }
                }
                """, StandardCharsets.UTF_8);
        MinosApplication reopened = MinosApplication.open(home);
        ProgramGraph rejected = reopened.programGraphService().getGraph(project.id().toString());
        assertFalse(rejected.supports(ProgramGraphCapability.CONTROL_FLOW));
        assertTrue(rejected.limitations().contains(
                FingerprintConstrainedJavaProgramGraphProvider.SOURCE_MISMATCH_LIMITATION));
    }

    @Test
    void hostedModeIsExplicitAndRejectsUnknownConfiguration(@TempDir Path root) {
        String previous = System.getProperty(MinosApplication.HOSTED_MODE_PROPERTY);
        try {
            System.setProperty(MinosApplication.HOSTED_MODE_PROPERTY, "surprise");
            assertThrows(IllegalArgumentException.class,
                    () -> MinosApplication.open(root.resolve("invalid")));
        } finally {
            if (previous == null) {
                System.clearProperty(MinosApplication.HOSTED_MODE_PROPERTY);
            } else {
                System.setProperty(MinosApplication.HOSTED_MODE_PROPERTY, previous);
            }
        }
    }

    @Test
    void builderAcceptsInjectedRuntimeAndSnapshotPorts(@TempDir Path root) throws Exception {
        FileSymbolSnapshotStore snapshots = new FileSymbolSnapshotStore(
                root.resolve("custom-snapshots"));
        FileRuntimeObservationStore runtimeObservations = new FileRuntimeObservationStore(
                root.resolve("custom-runtime"));
        ProviderRuntimeManager runtime = new ProviderRuntimeManager() {
            @Override
            public List<ProviderRuntimeStatus> list() {
                return List.of();
            }

            @Override
            public ProviderRuntimeStatus inspect(String providerId) {
                throw new UnsupportedOperationException("test runtime");
            }

            @Override
            public ProviderRuntimeStatus install(String providerId) {
                throw new UnsupportedOperationException("test runtime");
            }

            @Override
            public IndexerExecutor executor(String providerId) {
                throw new UnsupportedOperationException("test runtime");
            }
        };
        SnapshotStager stager = request -> "test-staged";
        SnapshotPromoter promoter = (projectId, runId, stagedSnapshotId) -> { };

        MinosApplication application = MinosApplication.builder(root.resolve("home"))
                .snapshotStore(snapshots)
                .runtimeObservationStore(runtimeObservations)
                .providerRuntimeManager(runtime)
                .snapshotLifecycle(stager, promoter)
                .build();

        assertSame(snapshots, application.snapshotStore());
        assertSame(runtimeObservations, application.runtimeObservationStore());
        assertSame(runtime, application.providerRuntimeManager());
        assertSame(stager, application.snapshotStager());
        assertSame(promoter, application.snapshotPromoter());
    }

    private static Symbol javaSymbol(RegisteredProject project, String fileId) {
        SymbolLocation location = new SymbolLocation(
                fileId, 1, 0, 1, 1, PositionEncoding.UTF16_CODE_UNITS);
        Origin origin = new Origin(
                "p0-vertical-fixture",
                "FIXTURE",
                "1",
                "snapshot-p0-vertical",
                OriginType.OTHER);
        return new Symbol(
                "vertical-fixture-symbol",
                "demo/VerticalFixture#",
                SymbolIdentityQuality.STRUCTURAL_FALLBACK,
                project.id().toString(),
                null,
                fileId,
                null,
                SymbolKind.CLASS,
                "VerticalFixture",
                "demo.VerticalFixture",
                null,
                "java",
                location,
                ResolutionStatus.RESOLVED,
                origin,
                false,
                false,
                Set.of());
    }
}
