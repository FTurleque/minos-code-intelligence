package com.minos.adapter.scip.runtime;

import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;
import com.minos.orchestration.IndexerQualification;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.runtime.CommandLocator;
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
    void probesPinnedScipJavaWithSystemJdkAndPropagatesKotlinToTheWindowsRuntime() throws IOException {
        Path coursier = temporaryDirectory.resolve("cs.exe");

        List<String> command = ManagedScipProviderRuntimeManager.scipJavaInstallationProbe(coursier);

        assertEquals(List.of(
                coursier.toString(),
                "launch", "org.scip-code:scip-java:0.13.1",
                "--jvm", "system",
                "--main", "org.scip_code.scip_java.ScipJava",
                "--", "--version"
        ), command);

        if (CommandLocator.isWindows()) {
            Path project = temporaryDirectory.resolve("kotlin-project");
            Path runner = temporaryDirectory.resolve("scip-java-windows-runner.ps1");
            Files.createDirectories(project);
            Files.writeString(project.resolve("pom.xml"), "<project/>\n");
            Files.createFile(coursier);
            Files.createFile(runner);
            IndexerDescriptor descriptor = new IndexerDescriptor(
                    "scip-java", "0.13.1", "scip-java", Set.of(Language.KOTLIN), Set.of(), Set.of(),
                    IndexerQualification.QUALIFIED, 1, List.of());
            IndexingExecutionRequest request = new IndexingExecutionRequest(
                    UUID.randomUUID(), UUID.randomUUID(), project,
                    new IndexerSelection(Language.KOTLIN, descriptor));

            var plan = new ScipJavaProcessPlanFactory(coursier, "org.scip-code:scip-java:0.13.1", runner)
                    .create(request, temporaryDirectory.resolve("run"));
            int languageArgument = plan.command().indexOf("-Language");

            assertTrue(languageArgument >= 0);
            assertEquals("KOTLIN", plan.command().get(languageArgument + 1));
        }
    }

    @Test
    void createsStandaloneScipJavaCommandWithoutCoursierRuntimeDependency() {
        Path launcher = temporaryDirectory.resolve("scip-java");
        Path output = temporaryDirectory.resolve("run").resolve("index.scip");

        assertEquals(List.of(
                launcher.toAbsolutePath().normalize().toString(),
                "index", "--output", output.toAbsolutePath().normalize().toString()
        ), ScipJavaProcessPlanFactory.standaloneCommand(launcher, output));
    }

    @Test
    void stagesScipJavaBuildOutsideProjectAndSkipsGeneratedTreesAndHostMavenWrappers() throws IOException {
        Path project = Files.createDirectories(temporaryDirectory.resolve("source-project"));
        Path source = Files.createDirectories(project.resolve("src/main/java/example"));
        Path generated = Files.createDirectories(project.resolve("target/classes"));
        Path mavenConfig = Files.createDirectories(project.resolve(".mvn"));
        Files.writeString(project.resolve("pom.xml"), "<project/>\n");
        Files.writeString(project.resolve("mvnw"), "#!/bin/sh\r\nexec mvn \"$@\"\r\n");
        Files.writeString(project.resolve("mvnw.cmd"), "@echo off\r\nmvn %*\r\n");
        Files.writeString(mavenConfig.resolve("maven.config"), "--no-transfer-progress\n");
        Files.writeString(source.resolve("Greeting.java"), "package example; final class Greeting {}\n");
        Files.writeString(generated.resolve("Greeting.class"), "generated");

        Path run = Files.createDirectories(temporaryDirectory.resolve("run"));
        Path workspace = ScipJavaProcessPlanFactory.prepareWritableWorkspace(project, run);

        assertEquals(run.resolve("workspace").toAbsolutePath().normalize(), workspace);
        assertFalse(workspace.startsWith(project.toAbsolutePath().normalize()));
        assertTrue(Files.isRegularFile(workspace.resolve("pom.xml")));
        assertTrue(Files.isRegularFile(workspace.resolve("src/main/java/example/Greeting.java")));
        assertTrue(Files.isRegularFile(workspace.resolve(".mvn/maven.config")),
                "project Maven configuration must remain available to the image-provided Maven runtime");
        assertFalse(Files.exists(workspace.resolve("mvnw")),
                "Linux/Docker staging must not let scip-java execute a host-materialized Maven wrapper");
        assertFalse(Files.exists(workspace.resolve("mvnw.cmd")),
                "Windows Maven wrapper launchers are never part of the Linux staging execution contract");
        assertFalse(Files.exists(workspace.resolve("target")),
                "pre-existing Maven outputs must never be imported into the writable staging tree");

        Path providerTarget = workspace.resolve("target/scip-targetroot");
        Files.createDirectories(providerTarget);
        Files.writeString(providerTarget.resolve("probe.txt"), "writable");
        assertTrue(Files.isRegularFile(providerTarget.resolve("probe.txt")));
        assertTrue(Files.isRegularFile(project.resolve("target/classes/Greeting.class")),
                "staging must not mutate the original project tree");
        assertTrue(Files.isRegularFile(project.resolve("mvnw")),
                "staging policy must not mutate the host checkout Maven wrapper");
    }

    @Test
    void requiresThePinnedVersionInTheInstallationProbeLog() throws IOException {
        Path validLog = temporaryDirectory.resolve("valid.log");
        Path invalidLog = temporaryDirectory.resolve("invalid.log");
        Files.writeString(validLog, "scip-java version 0.13.1\n");
        Files.writeString(invalidLog, "scip-java version 0.12.3\n");

        assertDoesNotThrow(() -> ManagedScipProviderRuntimeManager.requireExpectedScipJavaVersion(validLog));
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> ManagedScipProviderRuntimeManager.requireExpectedScipJavaVersion(invalidLog));
        assertTrue(failure.getMessage().contains("scip-java version 0.13.1"));
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
                .getResourceAsStream("scip-java-windows-runner.ps1")) {
            assertNotNull(input);
            String runner = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(runner.contains("maven-dependency-plugin:3.7.0:build-classpath"));
            assertTrue(runner.contains("--scip-config"));
            assertTrue(runner.contains("sourceFiles = @('src/main/kotlin', 'src/main/java')"));
            assertTrue(runner.contains("Remove-NewKotlinCompilerOutputs"));
        }
    }
}
