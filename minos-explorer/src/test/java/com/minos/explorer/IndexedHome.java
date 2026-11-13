package com.minos.explorer;

import com.minos.application.MinosApplication;
import com.minos.application.semantic.LocalHashEmbeddingProvider;
import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.PositionEncoding;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.domain.SymbolLocation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * A real MINOS home with one project of two source files, an active snapshot and a ready semantic index built
 * by the deterministic local-hash provider. One of the sources holds a script tag, to follow untrusted content
 * down to the answers.
 */
final class IndexedHome implements AutoCloseable {

    static final String PROJECT = "explorer-fixture";
    static final String SCRIPT = "<script>alert(1)</script>";

    final Path home;
    final Path projectRoot;
    final String projectId;
    final MinosApplication application;

    private IndexedHome(Path home, Path projectRoot, String projectId, MinosApplication application) {
        this.home = home;
        this.projectRoot = projectRoot;
        this.projectId = projectId;
        this.application = application;
    }

    static IndexedHome create(Path workDirectory) throws IOException {
        Path home = workDirectory.resolve("home");
        Path projectRoot = Files.createDirectories(workDirectory.resolve("project"));
        Files.createDirectories(projectRoot.resolve("src"));
        Files.writeString(projectRoot.resolve("src/Greeting.java"),
                "package fixture;\n\nclass GreetingService {\n    String greet() { return \"" + SCRIPT + "\"; }\n}\n",
                StandardCharsets.UTF_8);
        Files.writeString(projectRoot.resolve("src/Parting.java"),
                "package fixture;\n\nclass PartingService {\n    String part() { return \"bye\"; }\n}\n",
                StandardCharsets.UTF_8);
        String projectId;
        try (MinosApplication baseline = MinosApplication.open(home)) {
            var project = baseline.projectRegistry().registerProject(projectRoot, PROJECT);
            projectId = project.id().toString();
            baseline.snapshotStore().publish(project.id(), "snapshot-1", List.of(
                    symbol(projectId, "GreetingService", "src/Greeting.java"),
                    symbol(projectId, "PartingService", "src/Parting.java")), List.of(), List.of());
        }
        MinosApplication application = MinosApplication.builder(home)
                .embeddingProvider(new LocalHashEmbeddingProvider(64)).build();
        application.semanticIndexService().synchronize(PROJECT);
        return new IndexedHome(home, projectRoot, projectId, application);
    }

    @Override
    public void close() throws IOException {
        application.close();
    }

    private static Symbol symbol(String projectId, String name, String fileId) {
        return new Symbol(name, "fixture:" + name, SymbolIdentityQuality.CANONICAL, projectId,
                "module-a", fileId, null, SymbolKind.CLASS, name, "fixture." + name, "class " + name, "java",
                new SymbolLocation(fileId, 3, 0, 5, 1, PositionEncoding.UTF16_CODE_UNITS),
                ResolutionStatus.RESOLVED, new Origin("fixture", "TEST", "1", "run-1", OriginType.OTHER),
                false, false, Set.of());
    }
}
