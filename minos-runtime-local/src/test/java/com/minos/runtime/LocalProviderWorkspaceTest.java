package com.minos.runtime;

import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;
import com.minos.orchestration.IndexerQualification;
import com.minos.orchestration.IndexingMode;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.source.SourceBudgetPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalProviderWorkspaceTest {

    @TempDir
    Path temporary;

    @Test
    void copiesRegisteredProjectIntoIndependentScopedWorkspaceAndReclaimsIt() throws Exception {
        Path home = temporary.resolve("home");
        Path registered = temporary.resolve("project");
        Path module = registered.resolve("modules/app");
        Files.createDirectories(module);
        Files.writeString(registered.resolve("README.md"), "source-root");
        Files.writeString(module.resolve("Main.java"), "class Main {}\n");
        Files.writeString(registered.resolve(".minosignore"), "ignored.txt\n");
        Files.writeString(registered.resolve("ignored.txt"), "ignored");
        Files.createDirectories(registered.resolve(".git"));
        Files.writeString(registered.resolve(".git/config"), "must-not-be-copied");

        IndexingExecutionRequest original = request(registered, module, Path.of("modules/app"));
        Path copiedRoot;
        try (LocalProviderWorkspace workspace = LocalProviderWorkspace.create(home, original)) {
            copiedRoot = workspace.workspaceRoot();
            IndexingExecutionRequest isolated = workspace.request();

            assertNotEquals(original.registeredProjectRoot(), isolated.registeredProjectRoot());
            assertEquals(Path.of("modules/app"), isolated.projectRelativeRoot());
            assertEquals(copiedRoot.resolve("modules/app"), isolated.projectRoot());
            assertTrue(isolated.pathAuthorization().isPresent(),
                    "the copied filesystem identity must be captured before provider launch");
            assertEquals("source-root", Files.readString(copiedRoot.resolve("README.md")));
            assertEquals("class Main {}\n", Files.readString(copiedRoot.resolve("modules/app/Main.java")));
            assertFalse(Files.exists(copiedRoot.resolve("ignored.txt")));
            assertFalse(Files.exists(copiedRoot.resolve(".git")));

            Files.writeString(isolated.projectRoot().resolve("Main.java"), "provider mutation\n");
            assertEquals("class Main {}\n", Files.readString(module.resolve("Main.java")),
                    "provider writes must never mutate the registered source tree");
        }

        assertFalse(Files.exists(copiedRoot), "ephemeral local provider workspace must be reclaimed");
    }

    @Test
    void sourceBudgetFailureReclaimsPartialWorkspace() throws Exception {
        Path home = temporary.resolve("budget-home");
        Path project = temporary.resolve("budget-project");
        Files.createDirectories(project);
        Files.writeString(project.resolve("A.java"), "class A {}\n");
        Files.writeString(project.resolve("B.java"), "class B {}\n");
        IndexingExecutionRequest request = request(project, project, Path.of(""));

        IOException failure = assertThrows(
                IOException.class,
                () -> LocalProviderWorkspace.create(home, request, new SourceBudgetPolicy(1, 1024L * 1024L)));

        assertTrue(failure.getMessage().contains("source budget"));
        Path providerRoot = home.resolve("local-provider-workspaces")
                .resolve(request.runId().toString())
                .resolve("fake-provider");
        assertFalse(Files.exists(providerRoot), "failed copies must not leave provider-controlled residue");
    }

    private static IndexingExecutionRequest request(
            Path registeredRoot,
            Path projectRoot,
            Path relativeRoot
    ) {
        IndexerDescriptor descriptor = new IndexerDescriptor(
                "fake-provider", "1", "fake", Set.of(Language.JAVA), Set.of(), Set.of(),
                IndexerQualification.QUALIFIED, 1, List.of());
        return new IndexingExecutionRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                registeredRoot,
                projectRoot,
                relativeRoot,
                new IndexerSelection(Language.JAVA, descriptor),
                IndexingMode.FULL,
                List.of());
    }
}
