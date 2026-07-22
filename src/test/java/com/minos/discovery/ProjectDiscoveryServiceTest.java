package com.minos.discovery;

import com.minos.discovery.ProjectDiscovery.BuildSystem;
import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.discovery.ProjectDiscovery.SourceRootKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectDiscoveryServiceTest {

    private final ProjectDiscoveryService service = new ProjectDiscoveryService();

    @Test
    void discoversMavenMultiModuleProject() throws IOException {
        ProjectDiscovery discovery = service.discover(Path.of("fixtures/java/java-multi-module"));

        assertEquals(Set.of(Language.JAVA), discovery.languages());
        assertEquals(Set.of(BuildSystem.MAVEN), discovery.buildSystems());
        assertEquals(List.of("", "api", "app"), modulePaths(discovery));

        ProjectDiscovery.DiscoveredModule app = module(discovery, "app");
        assertTrue(app.sourceRoots().stream().anyMatch(root ->
                portable(root.relativePath()).equals("app/src/main/java")
                        && root.kind() == SourceRootKind.SOURCE
                        && root.language() == Language.JAVA));
        assertTrue(app.sourceRoots().stream().anyMatch(root ->
                portable(root.relativePath()).equals("app/src/test/java")
                        && root.kind() == SourceRootKind.TEST
                        && root.language() == Language.JAVA));
    }

    @Test
    void discoversNpmTypeScriptModules() throws IOException {
        ProjectDiscovery discovery = service.discover(Path.of("fixtures/typescript/typescript-modules"));

        assertEquals(Set.of(Language.TYPESCRIPT), discovery.languages());
        assertEquals(Set.of(BuildSystem.NPM), discovery.buildSystems());
        assertEquals(List.of("", "packages/api", "packages/app"), modulePaths(discovery));

        ProjectDiscovery.DiscoveredModule app = module(discovery, "packages/app");
        assertTrue(app.sourceRoots().stream().anyMatch(root ->
                portable(root.relativePath()).equals("packages/app/src")
                        && root.kind() == SourceRootKind.SOURCE
                        && root.language() == Language.TYPESCRIPT));
        assertTrue(app.sourceRoots().stream().anyMatch(root ->
                portable(root.relativePath()).equals("packages/app/test")
                        && root.kind() == SourceRootKind.TEST
                        && root.language() == Language.TYPESCRIPT));
    }

    @Test
    void skipsGeneratedDependencyDirectories(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("package.json"), "{}");
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/app.ts"), "export const app = 1;");

        Path nestedDependency = root.resolve("node_modules/fake-package");
        Files.createDirectories(nestedDependency.resolve("src"));
        Files.writeString(nestedDependency.resolve("package.json"), "{}");
        Files.writeString(nestedDependency.resolve("src/ignored.ts"), "export const ignored = true;");

        ProjectDiscovery discovery = service.discover(root);

        assertEquals(Set.of(Language.TYPESCRIPT), discovery.languages());
        assertEquals(List.of(""), modulePaths(discovery));
        assertFalse(discovery.modules().stream()
                .flatMap(module -> module.sourceRoots().stream())
                .anyMatch(sourceRoot -> portable(sourceRoot.relativePath()).contains("node_modules")));
    }

    @Test
    void appliesGitignoreAndMinosignoreToModulesAndSourceRoots(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Path mainJava = root.resolve("src/main/java/example");
        Path testJava = root.resolve("src/test/java/example");
        Files.createDirectories(mainJava);
        Files.createDirectories(testJava);
        Files.writeString(mainJava.resolve("App.java"), "package example; class App {}");
        Files.writeString(testJava.resolve("AppTest.java"), "package example; class AppTest {}");

        Path ignoredModule = root.resolve("ignored-module");
        Files.createDirectories(ignoredModule.resolve("src/main/java/example"));
        Files.writeString(ignoredModule.resolve("pom.xml"), "<project/>");
        Files.writeString(
                ignoredModule.resolve("src/main/java/example/Ignored.java"),
                "package example; class Ignored {}"
        );

        Files.writeString(root.resolve(".gitignore"), "ignored-module/\n");
        Files.writeString(root.resolve(".minosignore"), "src/test/java/\n");

        ProjectDiscovery discovery = service.discover(root);

        assertEquals(Set.of(Language.JAVA), discovery.languages());
        assertEquals(Set.of(BuildSystem.MAVEN), discovery.buildSystems());
        assertEquals(List.of(""), modulePaths(discovery));

        ProjectDiscovery.DiscoveredModule rootModule = module(discovery, "");
        assertTrue(rootModule.sourceRoots().stream().anyMatch(sourceRoot ->
                portable(sourceRoot.relativePath()).equals("src/main/java")
                        && sourceRoot.kind() == SourceRootKind.SOURCE));
        assertFalse(rootModule.sourceRoots().stream().anyMatch(sourceRoot ->
                portable(sourceRoot.relativePath()).equals("src/test/java")));
    }

    private static List<String> modulePaths(ProjectDiscovery discovery) {
        return discovery.modules().stream()
                .map(module -> portable(module.relativePath()))
                .toList();
    }

    private static ProjectDiscovery.DiscoveredModule module(ProjectDiscovery discovery, String relativePath) {
        return discovery.modules().stream()
                .filter(module -> portable(module.relativePath()).equals(relativePath))
                .findFirst()
                .orElseThrow();
    }

    private static String portable(Path path) {
        return path.toString().replace('\\', '/');
    }
}
