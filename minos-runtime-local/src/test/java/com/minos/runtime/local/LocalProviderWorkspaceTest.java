package com.minos.runtime.local;

import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;
import com.minos.orchestration.IndexerQualification;
import com.minos.orchestration.IndexingMode;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.source.SourceBudgetPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
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

    /** MINOS-AUD-A02 : la copie d'un run mort, plus vieille que la durée de vie des résidus, est supprimée. */
    @Test
    void creatingAWorkspaceReclaimsTheOrphanWorkspaceOfADeadRunOlderThanTheLifetime() throws Exception {
        Path home = temporary.resolve("orphan-home");
        Path project = temporary.resolve("orphan-project");
        Files.createDirectories(project);
        Files.writeString(project.resolve("A.java"), "class A {}\n");
        Path orphan = plantRunResidue(home, UUID.randomUUID(), Duration.ofHours(48));
        Path recent = plantRunResidue(home, UUID.randomUUID(), Duration.ofMinutes(5));

        try (LocalProviderWorkspace workspace = LocalProviderWorkspace.create(
                home, request(project, project, Path.of("")))) {
            assertFalse(Files.exists(orphan), "a 48 hour old residue of a dead run must be reclaimed");
            assertTrue(Files.exists(recent), "a recent run directory must never be reclaimed");
            assertTrue(Files.isDirectory(workspace.workspaceRoot()));
        }
    }

    @Test
    void aResidueDatedInTheFutureIsNeverReclaimed() throws Exception {
        Path home = temporary.resolve("future-home");
        Path project = temporary.resolve("future-project");
        Files.createDirectories(project);
        Files.writeString(project.resolve("A.java"), "class A {}\n");
        Path future = plantRunResidue(home, UUID.randomUUID(), Duration.ofHours(-72));

        try (LocalProviderWorkspace ignored = LocalProviderWorkspace.create(
                home, request(project, project, Path.of("")))) {
            assertTrue(Files.exists(future), "a residue dated in the future (clock jump) must be kept");
        }
    }

    @Test
    void aDirectoryThatIsNotNamedByARunIdentifierIsNeverReclaimed() throws Exception {
        Path home = temporary.resolve("foreign-home");
        Path project = temporary.resolve("foreign-project");
        Files.createDirectories(project);
        Files.writeString(project.resolve("A.java"), "class A {}\n");
        Path foreign = Files.createDirectories(home.resolve("local-provider-workspaces").resolve("not-a-run"));
        Files.writeString(foreign.resolve("keep.txt"), "mine");
        Files.setLastModifiedTime(foreign, FileTime.from(Instant.now().minus(Duration.ofDays(30))));

        try (LocalProviderWorkspace ignored = LocalProviderWorkspace.create(
                home, request(project, project, Path.of("")))) {
            assertTrue(Files.exists(foreign.resolve("keep.txt")), "only run directories are residues of a run");
        }
    }

    /** MINOS-AUD-A02 : le balayage est une maintenance, jamais une cause d'échec de la création. */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aResidueThatCannotBeRemovedDoesNotFailTheCreationOfTheWorkspace() throws Exception {
        Path home = temporary.resolve("stuck-home");
        Path project = temporary.resolve("stuck-project");
        Files.createDirectories(project);
        Files.writeString(project.resolve("A.java"), "class A {}\n");
        Path stuck = plantRunResidue(home, UUID.randomUUID(), Duration.ofHours(48));

        try (java.io.RandomAccessFile handle = new java.io.RandomAccessFile(
                stuck.resolve("fake-provider").resolve("workspace").resolve("f.txt").toFile(), "r");
             LocalProviderWorkspace workspace = LocalProviderWorkspace.create(
                     home, request(project, project, Path.of("")))) {
            assertTrue(handle.length() > 0);
            assertTrue(Files.isDirectory(workspace.workspaceRoot()), "the creation must succeed");
            assertTrue(Files.exists(stuck), "a residue that cannot be removed is left in place");
        }
    }

    /** A run directory as a killed MINOS leaves it: the copy is there and nobody owns it any more. */
    private static Path plantRunResidue(Path home, UUID runId, Duration age) throws IOException {
        Path run = Files.createDirectories(home.resolve("local-provider-workspaces").resolve(runId.toString()));
        Path workspace = Files.createDirectories(run.resolve("fake-provider").resolve("workspace"));
        Files.writeString(workspace.resolve("f.txt"), "residue");
        FileTime time = FileTime.from(Instant.now().minus(age));
        Files.setLastModifiedTime(workspace.resolve("f.txt"), time);
        Files.setLastModifiedTime(workspace, time);
        Files.setLastModifiedTime(run.resolve("fake-provider"), time);
        Files.setLastModifiedTime(run, time);
        return run;
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
