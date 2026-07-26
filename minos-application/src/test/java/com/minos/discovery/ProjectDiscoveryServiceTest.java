package com.minos.discovery;

import com.minos.discovery.ProjectDiscovery.BuildSystem;
import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.discovery.ProjectDiscovery.SourceRoot;
import com.minos.discovery.ProjectDiscovery.SourceRootKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
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
        assertTrue(hasRoot(app, "app/src/main/java", SourceRootKind.SOURCE, Language.JAVA));
        assertTrue(hasRoot(app, "app/src/test/java", SourceRootKind.TEST, Language.JAVA));
    }

    @Test
    void discoversNpmTypeScriptWorkspace() throws IOException {
        ProjectDiscovery discovery = service.discover(Path.of("fixtures/typescript/typescript-modules"));
        assertEquals(Set.of(Language.TYPESCRIPT), discovery.languages());
        assertEquals(Set.of(BuildSystem.NPM), discovery.buildSystems());
        assertEquals(List.of("", "packages/api", "packages/app"), modulePaths(discovery));
        assertTrue(module(discovery, "packages/app").buildSystems().contains(BuildSystem.NPM));
    }

    @Test
    void discoversGradleJavaAndKotlinMultiModuleFixtures() throws IOException {
        ProjectDiscovery javaProject = service.discover(Path.of("fixtures/gradle/gradle-java-simple"));
        assertEquals(Set.of(Language.JAVA), javaProject.languages());
        assertEquals(Set.of(BuildSystem.GRADLE), javaProject.buildSystems());

        ProjectDiscovery kotlinProject = service.discover(Path.of("fixtures/gradle/gradle-kotlin-multi"));
        assertEquals(Set.of(Language.KOTLIN), kotlinProject.languages());
        assertEquals(Set.of(BuildSystem.GRADLE), kotlinProject.buildSystems());
        assertEquals(List.of("", "app", "core"), modulePaths(kotlinProject));
        assertTrue(hasRoot(module(kotlinProject, "app"), "app/src/main/kotlin", SourceRootKind.SOURCE, Language.KOTLIN));
    }

    @Test
    void discoversPnpmWorkspaceAndInheritsWorkspaceBuildSystem() throws IOException {
        ProjectDiscovery discovery = service.discover(Path.of("fixtures/typescript/typescript-pnpm-workspace"));
        assertEquals(Set.of(Language.TYPESCRIPT), discovery.languages());
        assertEquals(Set.of(BuildSystem.PNPM), discovery.buildSystems());
        assertEquals(List.of("", "packages/api"), modulePaths(discovery));
        assertEquals(Set.of(BuildSystem.PNPM), module(discovery, "packages/api").buildSystems());
    }

    @Test
    void discoversYarnWorkspaceThroughSameBuildSystemSpi(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("package.json"), "{\"private\":true}");
        Files.writeString(root.resolve("yarn.lock"), "# fixture\n");
        Path packageRoot = root.resolve("packages/app");
        Files.createDirectories(packageRoot.resolve("src"));
        Files.writeString(packageRoot.resolve("package.json"), "{\"name\":\"app\"}");
        Files.writeString(packageRoot.resolve("src/app.ts"), "export const value = 1;");

        ProjectDiscovery discovery = service.discover(root);
        assertEquals(Set.of(BuildSystem.YARN), discovery.buildSystems());
        assertEquals(Set.of(BuildSystem.YARN), module(discovery, "packages/app").buildSystems());
        assertEquals(Set.of(Language.TYPESCRIPT), discovery.languages());
    }

    @Test
    void discoversKotlinMavenAndPythonFixtures() throws IOException {
        ProjectDiscovery kotlin = service.discover(Path.of("fixtures/kotlin/kotlin-maven-simple"));
        assertEquals(Set.of(Language.KOTLIN), kotlin.languages());
        assertEquals(Set.of(BuildSystem.MAVEN), kotlin.buildSystems());

        ProjectDiscovery python = service.discover(Path.of("fixtures/python/python-simple"));
        assertEquals(Set.of(Language.PYTHON), python.languages());
        assertTrue(python.buildSystems().isEmpty());
        assertTrue(hasRoot(module(python, ""), "src", SourceRootKind.SOURCE, Language.PYTHON));
        assertTrue(hasRoot(module(python, ""), "tests", SourceRootKind.TEST, Language.PYTHON));
    }

    @Test
    void acceptsNewDiscoveryPluginWithoutChangingCentralOrchestrator(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("custom.project"), "custom");
        Files.createDirectories(root.resolve("code"));
        Files.writeString(root.resolve("code/main.py"), "value = 1\n");

        ProjectDiscoveryService pluginService = new ProjectDiscoveryService(
                List.of((projectRoot, directory, ignores) -> Files.isRegularFile(directory.resolve("custom.project"))),
                List.of((projectRoot, moduleRoot, ignores) -> Optional.of(BuildSystem.YARN)),
                List.of((projectRoot, moduleRoot, ignores) -> List.of(
                        new SourceRoot(projectRoot.relativize(moduleRoot.resolve("code")), SourceRootKind.SOURCE, Language.PYTHON))),
                List.of(file -> file.toString().endsWith(".py") ? Optional.of(Language.PYTHON) : Optional.empty())
        );

        ProjectDiscovery discovery = pluginService.discover(root);
        assertEquals(Set.of(Language.PYTHON), discovery.languages());
        assertEquals(Set.of(BuildSystem.YARN), discovery.buildSystems());
        assertEquals(List.of(""), modulePaths(discovery));
    }

    @Test
    void exposesComposableLanguageDetectors() {
        assertTrue(service.languageDetectors().stream()
                .map(detector -> detector.detect(Path.of("Example.kt")))
                .anyMatch(Optional.of(Language.KOTLIN)::equals));
        assertTrue(service.languageDetectors().stream()
                .map(detector -> detector.detect(Path.of("example.py")))
                .anyMatch(Optional.of(Language.PYTHON)::equals));
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
        assertFalse(discovery.modules().stream().flatMap(value -> value.sourceRoots().stream())
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
        Files.writeString(ignoredModule.resolve("src/main/java/example/Ignored.java"), "package example; class Ignored {}");
        Files.writeString(root.resolve(".gitignore"), "ignored-module/\n");
        Files.writeString(root.resolve(".minosignore"), "src/test/java/\n");
        ProjectDiscovery discovery = service.discover(root);
        assertEquals(Set.of(Language.JAVA), discovery.languages());
        assertEquals(Set.of(BuildSystem.MAVEN), discovery.buildSystems());
        assertEquals(List.of(""), modulePaths(discovery));
        ProjectDiscovery.DiscoveredModule rootModule = module(discovery, "");
        assertTrue(hasRoot(rootModule, "src/main/java", SourceRootKind.SOURCE, Language.JAVA));
        assertFalse(rootModule.sourceRoots().stream().anyMatch(sourceRoot -> portable(sourceRoot.relativePath()).equals("src/test/java")));
    }

    private static boolean hasRoot(ProjectDiscovery.DiscoveredModule module, String path, SourceRootKind kind, Language language) {
        return module.sourceRoots().stream().anyMatch(root -> portable(root.relativePath()).equals(path)
                && root.kind() == kind && root.language() == language);
    }

    private static List<String> modulePaths(ProjectDiscovery discovery) {
        return discovery.modules().stream().map(module -> portable(module.relativePath())).toList();
    }

    private static ProjectDiscovery.DiscoveredModule module(ProjectDiscovery discovery, String relativePath) {
        return discovery.modules().stream()
                .filter(module -> portable(module.relativePath()).equals(relativePath))
                .findFirst().orElseThrow();
    }

    private static String portable(Path path) {
        return path.toString().replace('\\', '/');
    }
}
