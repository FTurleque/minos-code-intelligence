package com.minos.adapter.scip.runtime.plan;

import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;
import com.minos.orchestration.IndexerQualification;
import com.minos.orchestration.IndexingMode;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.runtime.local.process.IndexerProcessPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Le plan Linux de scip-java place les fichiers intermédiaires dans le run, hors de la copie de travail : un module
 * qui construit dans le {@code target/} racine ne peut plus les effacer par son {@code clean}.
 */
@DisabledOnOs(OS.WINDOWS)
class ScipJavaProcessPlanFactoryTargetRootTest {

    private static final List<String> NAMES = List.of("scip-java-0.13.1.jar", "scala-library-2.13.16.jar");

    @TempDir
    Path temp;

    @Test
    void theLinuxPlanGivesScipJavaATargetRootInTheRunOutsideTheWorkspace() throws IOException {
        String javaHome = System.getenv("JAVA_HOME");
        assumeTrue(javaHome != null && !javaHome.isBlank()
                        && Files.isRegularFile(Path.of(javaHome).resolve("bin").resolve("javac")),
                "the plan requires a project JDK in JAVA_HOME");
        Path classpath = Files.createDirectories(temp.resolve("classpath"));
        for (String name : NAMES) Files.writeString(classpath.resolve(name), name);
        Files.write(classpath.resolve(ScipJavaClasspath.LAUNCHER), ScipJavaClasspath.launcherBytes(NAMES));
        Path listing = Files.write(classpath.resolve(ScipJavaClasspath.LISTING), ScipJavaClasspath.listingBytes(NAMES));
        Path project = Files.createDirectories(temp.resolve("reactor"));
        Files.writeString(project.resolve("pom.xml"), "<project/>\n");
        Path run = temp.resolve("run");
        ScipJavaProcessPlanFactory factory = new ScipJavaProcessPlanFactory(
                temp.resolve("cs"), "org.scip-code:scip-java:0.13.1", temp.resolve("runner.ps1"),
                temp.resolve("mvn"), listing, jars -> { }, ScipJavaMavenOfflineFixtures.qualified(temp.resolve("maven")));

        IndexerProcessPlan plan = factory.create(request(project), run);

        List<String> command = plan.command();
        Path targetRoot = run.toAbsolutePath().normalize().resolve("scip-java-targetroot");
        int targetRootFlag = command.indexOf("--targetroot");
        assertEquals(targetRoot.toString(), command.get(targetRootFlag + 1), command::toString);
        assertFalse(targetRoot.startsWith(plan.workingDirectory().toAbsolutePath().normalize()),
                "the targetroot must be outside the staged project build tree");
    }

    private static IndexingExecutionRequest request(Path project) {
        IndexerDescriptor descriptor = new IndexerDescriptor(
                "scip-java", "0.13.1", "scip-java", Set.of(Language.JAVA), Set.of(), Set.of(),
                IndexerQualification.QUALIFIED, 1, List.of());
        return new IndexingExecutionRequest(
                UUID.randomUUID(), UUID.randomUUID(), project,
                new IndexerSelection(Language.JAVA, descriptor), IndexingMode.FULL, List.of());
    }
}
