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
import com.minos.io.PrivateLocalStorage;
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
import com.minos.storage.MinosRuntimeSettings;
import com.minos.storage.StorageBackendConfiguration;
import com.minos.store.FileRuntimeObservationStore;
import com.minos.store.FileSymbolSnapshotStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinosApplicationTest {

    @AfterEach
    void resetCapabilityProbe() {
        PrivateLocalStorage.resetCapabilityProbeForTesting();
    }

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
    void opensMakesMinosHomeItselfPrivateNotJustTheStoresCreatedInsideIt(@TempDir Path root) throws Exception {
        Path home = root.resolve("minos-home");

        MinosApplication.open(home).close();

        assertEquals(PrivateLocalStorage.Privacy.ENFORCED, PrivateLocalStorage.privacyOf(home));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void opensHardensAPreExistingWorldReadableMinosHomeRoot(@TempDir Path root) throws Exception {
        Path home = Files.createDirectories(root.resolve("legacy-minos-home"));
        Files.setPosixFilePermissions(home, PosixFilePermissions.fromString("rwxr-xr-x"));

        MinosApplication.open(home).close();

        assertEquals("rwx------", PosixFilePermissions.toString(
                Files.getPosixFilePermissions(home, LinkOption.NOFOLLOW_LINKS)));
    }

    // ---------------------------------------------------------------- home validated before use
    //
    // These prove an ORDER, not just a thrown exception: home must be confirmed private before
    // open() reads config/minos.properties, resolves a storage backend, or opens one. Each test
    // poisons the config a real settings/backend read would reach with something that fails
    // differently and distinctively from the expected private-storage rejection, so that if
    // validation happened too late, the assertion on the failure's message (not just its type)
    // would catch it.

    @Test
    void opensRejectsASymlinkedHomeBeforeReadingConfiguration(@TempDir Path root) throws Exception {
        Path real = Files.createDirectories(root.resolve("real-home"));
        Path configDir = Files.createDirectories(real.resolve(MinosRuntimeSettings.CONFIG_DIRECTORY));
        Files.writeString(configDir.resolve(MinosRuntimeSettings.CONFIG_FILE),
                StorageBackendConfiguration.BACKEND_PROPERTY + "=not-a-real-backend\n", StandardCharsets.UTF_8);
        Path link = root.resolve("home-link");
        try {
            Files.createSymbolicLink(link, real);
        } catch (UnsupportedOperationException | IOException unsupported) {
            return; // symlink creation is not available here; nothing to assert
        }

        IOException failure = assertThrows(IOException.class, () -> MinosApplication.open(link));

        // Had configuration/backend resolution run first, this would instead be an
        // IllegalArgumentException("unsupported storage backend: not-a-real-backend"): proof the
        // config file the symlink points at was never read.
        assertTrue(failure.getMessage().contains("symbolic link"), failure.getMessage());
    }

    @Test
    void opensRejectsAnUnenforceableHomeBeforeReadingConfiguration(@TempDir Path root) {
        Path home = root.resolve("unenforceable-home");
        PrivateLocalStorage.useForTesting(new PrivateLocalStorage.CapabilityProbe() {
            @Override
            public boolean supportsPosix(Path target) {
                return false;
            }

            @Override
            public AclFileAttributeView aclView(Path target) {
                return null;
            }
        });

        IOException failure = assertThrows(IOException.class, () -> MinosApplication.open(home));

        assertTrue(failure.getMessage().contains("neither POSIX"), failure.getMessage());
        assertFalse(Files.exists(home, LinkOption.NOFOLLOW_LINKS),
                "home must not be left behind, and no configuration directory should ever have been reached inside it");
    }

    @Test
    void opensValidatesHomeBeforeAttemptingAPostgresConnection(@TempDir Path root) throws Exception {
        Path real = Files.createDirectories(root.resolve("real-home-pg"));
        Path configDir = Files.createDirectories(real.resolve(MinosRuntimeSettings.CONFIG_DIRECTORY));
        Files.writeString(configDir.resolve(MinosRuntimeSettings.CONFIG_FILE), String.join("\n",
                StorageBackendConfiguration.BACKEND_PROPERTY + "=postgresql",
                StorageBackendConfiguration.POSTGRES_URL_PROPERTY + "=jdbc:postgresql://127.0.0.1:1/minos",
                StorageBackendConfiguration.POSTGRES_USER_PROPERTY + "=test",
                StorageBackendConfiguration.POSTGRES_PASSWORD_PROPERTY + "=test",
                ""), StandardCharsets.UTF_8);
        Path link = root.resolve("home-link-pg");
        try {
            Files.createSymbolicLink(link, real);
        } catch (UnsupportedOperationException | IOException unsupported) {
            return;
        }

        IOException failure = assertThrows(IOException.class, () -> MinosApplication.open(link));

        // Actually attempting this connection (port 1 refuses immediately) would fail with a
        // connection-refused style message, not this one -- proof MINOS never tried to open the
        // PostgreSQL backend at all.
        assertTrue(failure.getMessage().contains("symbolic link"), failure.getMessage());
    }

    @Test
    void builderDirectlyRejectsASymlinkedHomeTooNotOnlyOpen(@TempDir Path root) throws Exception {
        Path real = Files.createDirectories(root.resolve("real-home-builder"));
        Path link = root.resolve("home-link-builder");
        try {
            Files.createSymbolicLink(link, real);
        } catch (UnsupportedOperationException | IOException unsupported) {
            return;
        }

        IOException failure = assertThrows(IOException.class, () -> MinosApplication.builder(link).build());
        assertTrue(failure.getMessage().contains("symbolic link"), failure.getMessage());
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
