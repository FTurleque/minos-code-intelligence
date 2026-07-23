package com.minos.architecture;

import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.registry.LocalProjectRegistry;
import com.minos.registry.RegisteredProject;
import com.minos.store.FileSymbolSnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LocalProjectArchitectureQueryTest {

    @Test
    void reloadsRegisteredProjectDiscoveryAndActiveSnapshot(@TempDir Path root) throws Exception {
        Path projectRoot = Files.createDirectories(root.resolve("project"));
        Files.writeString(projectRoot.resolve("pom.xml"), "<project/>");
        Path sourceDirectory = Files.createDirectories(
                projectRoot.resolve("src/main/java/com/acme"));
        Files.writeString(
                sourceDirectory.resolve("App.java"),
                "package com.acme; public final class App {}"
        );

        LocalProjectRegistry registry = new LocalProjectRegistry(root.resolve("registry"));
        RegisteredProject project = registry.registerProject(projectRoot, "architecture-fixture");
        FileSymbolSnapshotStore snapshots = new FileSymbolSnapshotStore(root.resolve("snapshots"));
        snapshots.publish(
                project.id(),
                "snapshot-m6-local",
                List.of(symbol(project, "src/main/java/com/acme/App.java")),
                List.of(),
                List.of()
        );

        ArchitectureOverview overview = new LocalProjectArchitectureQuery(registry, snapshots)
                .getArchitectureOverview("architecture-fixture");

        assertEquals(project.id().toString(), overview.projectId());
        assertEquals("snapshot-m6-local", overview.snapshotId());
        assertEquals(List.of("JAVA"), overview.languages());
        assertEquals(List.of("MAVEN"), overview.buildSystems());
        assertEquals(1, overview.localSymbolCount());
        assertEquals(0, overview.unassignedLocalSymbolCount());
        assertEquals(1, overview.moduleCount());
        assertEquals("com.acme", overview.modules().getFirst().namespaces().getFirst().name());
    }

    private static Symbol symbol(RegisteredProject project, String fileId) {
        return new Symbol(
                "sym:app",
                "key:app",
                SymbolIdentityQuality.STRUCTURAL_FALLBACK,
                project.id().toString(),
                "main",
                fileId,
                null,
                SymbolKind.CLASS,
                "App",
                "com.acme.App",
                null,
                "java",
                null,
                ResolutionStatus.RESOLVED,
                new Origin("test", "TEST", "1", "run", OriginType.OTHER),
                false,
                false,
                Set.of()
        );
    }
}
