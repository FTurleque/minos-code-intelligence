package com.minos.discovery;

import com.minos.discovery.ProjectDiscovery.BuildSystem;
import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.discovery.ProjectDiscovery.SourceRootKind;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class M24PolyglotDiscoveryTest {
    private final ProjectDiscoveryService discovery = new ProjectDiscoveryService();

    @Test
    void discoversMixedCAndCppCmakeFixtureThroughExistingSpis() throws Exception {
        ProjectDiscovery project = discovery.discover(Path.of("fixtures/m24/clang"));
        assertEquals(Set.of(Language.C, Language.CPP), project.languages());
        assertEquals(Set.of(BuildSystem.CMAKE), project.buildSystems());
        ProjectDiscovery.DiscoveredModule root = rootModule(project);
        assertTrue(hasRoot(root, "src", SourceRootKind.SOURCE, Language.C));
        assertTrue(hasRoot(root, "src", SourceRootKind.SOURCE, Language.CPP));
        assertTrue(hasRoot(root, "include", SourceRootKind.SOURCE, Language.C));
    }

    @Test
    void discoversCsharpDotnetFixture() throws Exception {
        ProjectDiscovery project = discovery.discover(Path.of("fixtures/m24/csharp"));
        assertEquals(Set.of(Language.CSHARP), project.languages());
        assertEquals(Set.of(BuildSystem.DOTNET), project.buildSystems());
        assertTrue(hasRoot(rootModule(project), "src", SourceRootKind.SOURCE, Language.CSHARP));
    }

    @Test
    void discoversGoModuleFixtureWithRootSourceRoot() throws Exception {
        ProjectDiscovery project = discovery.discover(Path.of("fixtures/m24/go"));
        assertEquals(Set.of(Language.GO), project.languages());
        assertEquals(Set.of(BuildSystem.GO_MODULE), project.buildSystems());
        assertTrue(hasRoot(rootModule(project), "", SourceRootKind.SOURCE, Language.GO));
    }

    @Test
    void discoversRustCargoFixture() throws Exception {
        ProjectDiscovery project = discovery.discover(Path.of("fixtures/m24/rust"));
        assertEquals(Set.of(Language.RUST), project.languages());
        assertEquals(Set.of(BuildSystem.CARGO), project.buildSystems());
        assertTrue(hasRoot(rootModule(project), "src", SourceRootKind.SOURCE, Language.RUST));
    }

    private static ProjectDiscovery.DiscoveredModule rootModule(ProjectDiscovery project) {
        return project.modules().stream()
                .filter(module -> portable(module.relativePath()).isEmpty())
                .findFirst()
                .orElseThrow();
    }

    private static boolean hasRoot(
            ProjectDiscovery.DiscoveredModule module,
            String path,
            SourceRootKind kind,
            Language language
    ) {
        return module.sourceRoots().stream().anyMatch(root -> portable(root.relativePath()).equals(path)
                && root.kind() == kind && root.language() == language);
    }

    private static String portable(Path path) {
        return path.toString().replace('\\', '/');
    }
}
