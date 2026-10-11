package com.minos.adapter.scip.runtime.install;

import com.minos.adapter.scip.runtime.plan.ScipJavaProcessPlanFactory;
import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;
import com.minos.orchestration.IndexerQualification;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.runtime.local.process.CommandLocator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedScipProviderRuntimeManagerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void fetchesPinnedScipJavaWithoutLaunchingItAndPropagatesKotlinToTheWindowsRuntime() throws IOException {
        Path coursier = temporaryDirectory.resolve("cs.exe");

        List<String> command = ManagedScipProviderRuntimeManager.scipJavaFetchCommand(coursier);

        // Resolution only: scip-java is never launched, and nothing runs before the jars are verified.
        assertEquals(List.of(coursier.toString(), "fetch", "--classpath", "org.scip-code:scip-java:0.13.1"), command);
        assertFalse(command.contains("launch"));
        assertFalse(command.contains("--main"));

        if (CommandLocator.isWindows()) {
            Path project = temporaryDirectory.resolve("kotlin-project");
            Path runner = temporaryDirectory.resolve("scip-java-windows-runner.ps1");
            Path maven = temporaryDirectory.resolve("mvn.cmd");
            Files.createDirectories(project);
            Files.writeString(project.resolve("pom.xml"), "<project/>\n");
            Files.createFile(coursier);
            Files.createFile(runner);
            Files.createFile(maven);
            IndexerDescriptor descriptor = new IndexerDescriptor(
                    "scip-java", "0.13.1", "scip-java", Set.of(Language.KOTLIN), Set.of(), Set.of(),
                    IndexerQualification.QUALIFIED, 1, List.of());
            IndexingExecutionRequest request = new IndexingExecutionRequest(
                    UUID.randomUUID(), UUID.randomUUID(), project,
                    new IndexerSelection(Language.KOTLIN, descriptor));

            var plan = new ScipJavaProcessPlanFactory(coursier, "org.scip-code:scip-java:0.13.1", runner, maven)
                    .create(request, temporaryDirectory.resolve("run"));
            int languageArgument = plan.command().indexOf("-Language");
            int mavenArgument = plan.command().indexOf("-MavenCommand");

            assertTrue(languageArgument >= 0);
            assertEquals("KOTLIN", plan.command().get(languageArgument + 1));
            assertTrue(mavenArgument >= 0);
            assertEquals(maven.toString(), plan.command().get(mavenArgument + 1));
        }
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
