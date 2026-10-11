package com.minos.adapter.scip.runtime.plan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Commande autonome et préparation de l'espace de travail du plan d'indexation Java. */
class ScipJavaProcessPlanFactoryCommandTest {

    @TempDir
    Path temporaryDirectory;

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
}
