package com.minos.adapter.scip.runtime.install;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedScipProviderRuntimeManagerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void fetchesPinnedScipJavaWithoutLaunchingIt() {
        Path coursier = temporaryDirectory.resolve("cs.exe");

        List<String> command = ManagedScipProviderRuntimeManager.scipJavaFetchCommand(coursier);

        // Resolution only: scip-java is never launched, and nothing runs before the jars are verified.
        assertEquals(List.of(coursier.toString(), "fetch", "--classpath", "org.scip-code:scip-java:0.13.1"), command);
        assertFalse(command.contains("launch"));
        assertFalse(command.contains("--main"));
        // The Windows plan of a Kotlin project reads JAVA_HOME: it is proven in a child JVM with its own environment,
        // ScipJavaProcessPlanFactoryToolchainTest#onWindowsAKotlinProjectGivesItsLanguageToTheRunner (AUD-TST-18).
    }

    @Test
    void theWindowsRuntimeFilesAreCopiedUnderTheirPlainNameWhateverPackageLoadsThem() throws IOException {
        Path runtime = Files.createDirectories(temporaryDirectory.resolve("runtime"));

        ManagedScipProviderRuntimeManager.copyPackagedResource(
                "scip-java-windows-runner.ps1", runtime.resolve("scip-java-windows-runner.ps1"));
        ManagedScipProviderRuntimeManager.copyPackagedResource("ScipWriter.java", runtime.resolve("ScipWriter.java"));

        // A resource path made absolute by mistake would make Path.resolve ignore the runtime directory.
        assertTrue(Files.isRegularFile(runtime.resolve("scip-java-windows-runner.ps1")));
        assertTrue(Files.isRegularFile(runtime.resolve("ScipWriter.java")));
        assertTrue(Files.size(runtime.resolve("scip-java-windows-runner.ps1")) > 0);
    }

    @Test
    void packagesKotlinScipBuildToolSupportAndResolvesGitBashBesideGit() throws IOException {
        Path git = temporaryDirectory.resolve("Git").resolve("cmd").resolve("git.exe");
        Path gitBash = temporaryDirectory.resolve("Git").resolve("bin").resolve("bash.exe");
        Files.createDirectories(git.getParent());
        Files.createDirectories(gitBash.getParent());
        Files.createFile(git);
        Files.createFile(gitBash);

        assertEquals(gitBash.toAbsolutePath().normalize(),
                ManagedScipProviderRuntimeManager.gitBashForGit(git).orElseThrow());

        try (InputStream input = ManagedScipProviderRuntimeManager.class
                .getResourceAsStream(EmbeddedToolsCatalog.RESOURCE_ROOT + "scip-java-windows-runner.ps1")) {
            assertNotNull(input);
            String runner = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(runner.contains("maven-dependency-plugin:3.7.0:build-classpath"));
            assertTrue(runner.contains("--scip-config"));
            assertTrue(runner.contains("sourceFiles = @('src/main/kotlin', 'src/main/java')"));
            assertTrue(runner.contains("Remove-NewKotlinCompilerOutputs"));
        }
    }
}
