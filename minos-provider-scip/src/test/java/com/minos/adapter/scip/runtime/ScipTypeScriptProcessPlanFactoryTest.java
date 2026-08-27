package com.minos.adapter.scip.runtime;

import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;
import com.minos.orchestration.IndexerQualification;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScipTypeScriptProcessPlanFactoryTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void directNodeInvocationPreservesMainSymlinksAndPlacesTheEntryScriptSecondNeverGoingThroughCmd() throws IOException {
        Path node = Files.createFile(temporaryDirectory.resolve("node.exe"));
        Path mainScript = Files.createFile(temporaryDirectory.resolve("main.js"));
        Path project = Files.createDirectories(temporaryDirectory.resolve("project"));
        Files.writeString(project.resolve("tsconfig.json"), "{}\n");
        Path run = temporaryDirectory.resolve("run");

        var plan = new ScipTypeScriptProcessPlanFactory(node, mainScript)
                .create(request(project), run);

        Path output = run.resolve("index.scip").toAbsolutePath().normalize();
        assertEquals(List.of(
                node.toAbsolutePath().normalize().toString(),
                "--preserve-symlinks",
                "--preserve-symlinks-main",
                mainScript.toString(),
                "index", "--output", output.toString()
        ), plan.command());
    }

    @Test
    void addsInferTsconfigWhenOnlyPackageJsonIsPresent() throws IOException {
        Path node = Files.createFile(temporaryDirectory.resolve("node.exe"));
        Path mainScript = Files.createFile(temporaryDirectory.resolve("main.js"));
        Path project = Files.createDirectories(temporaryDirectory.resolve("project"));
        Files.writeString(project.resolve("package.json"), "{}\n");
        Path run = temporaryDirectory.resolve("run");

        var plan = new ScipTypeScriptProcessPlanFactory(node, mainScript)
                .create(request(project), run);

        assertEquals("--infer-tsconfig", plan.command().get(plan.command().size() - 1));
    }

    @Test
    void singleArgumentConstructorInvokesTheExecutableDirectlyWithoutPrependingAScript() throws IOException {
        Path executable = Files.createFile(temporaryDirectory.resolve("scip-typescript"));
        Path project = Files.createDirectories(temporaryDirectory.resolve("project"));
        Files.writeString(project.resolve("tsconfig.json"), "{}\n");
        Path run = temporaryDirectory.resolve("run");

        var plan = new ScipTypeScriptProcessPlanFactory(executable)
                .create(request(project), run);

        Path output = run.resolve("index.scip").toAbsolutePath().normalize();
        assertEquals(List.of(
                executable.toAbsolutePath().normalize().toString(),
                "index", "--output", output.toString()
        ), plan.command());
    }

    private static IndexingExecutionRequest request(Path project) {
        IndexerDescriptor descriptor = new IndexerDescriptor(
                "scip-typescript", "0.4.0", "scip-typescript", Set.of(Language.TYPESCRIPT), Set.of(), Set.of(),
                IndexerQualification.QUALIFIED, 1, List.of());
        return new IndexingExecutionRequest(
                UUID.randomUUID(), UUID.randomUUID(), project,
                new IndexerSelection(Language.TYPESCRIPT, descriptor));
    }
}
