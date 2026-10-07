package com.minos.runtime.local;

import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;
import com.minos.orchestration.IndexerQualification;
import com.minos.orchestration.IndexingMode;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.source.SourceBudgetPolicy;
import com.minos.testsupport.LogCapture;
import com.minos.testsupport.UnreadableDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MINOS-AUD-D01 (lacune de la fiche) : la copie de travail du provider avait le même {@code visitFileFailed} qui
 * relance. Sans la corriger, {@code minos index} échouait encore après la découverte et l'empreinte. Un répertoire
 * illisible ignoré ou durci est écarté de la copie ; un répertoire non ignoré échoue de façon actionnable et sans
 * résidu partiel. MINOS-AUD-D10 : le BOM d'un {@code .gitignore} n'altère pas sa première règle dans la copie non plus.
 * Ignorés pour un compte root ou administrateur élevé.
 */
class ProviderWorkspaceFilesUnreadableDirectoryTest {
    private static final SourceBudgetPolicy BUDGET = new SourceBudgetPolicy(1000, 16L * 1024L * 1024L);

    @Test
    void anIgnoredUnreadableDirectoryIsLeftOutOfTheCopy(@TempDir Path home) throws Exception {
        Path source = project(home);
        Files.writeString(source.resolve(".gitignore"), "pgdata/\n");
        Path pgdata = Files.createDirectories(source.resolve("pgdata"));
        Files.writeString(pgdata.resolve("PG_VERSION"), "16");
        Path target = home.resolve("copy");

        try (UnreadableDirectory ignored = UnreadableDirectory.denyOrSkip(pgdata);
             LogCapture logs = new LogCapture(ProviderWorkspaceFiles.class)) {
            ProviderWorkspaceFiles.copyWorkspace(source, target, BUDGET, "test workspace");

            assertEquals("class Main {}\n", Files.readString(target.resolve("src/Main.java")));
            assertFalse(Files.exists(target.resolve("pgdata")), "an ignored directory has no place in the provider copy");
            List<String> warnings = logs.warnings();
            assertEquals(1, warnings.size(), warnings.toString());
            assertTrue(warnings.getFirst().contains("'pgdata'"), warnings.getFirst());
            assertFalse(warnings.getFirst().contains(source.toString()), "no absolute path: " + warnings.getFirst());
        }
    }

    @Test
    void aHardenedUnreadableDirectoryIsLeftOutOfTheCopy(@TempDir Path home) throws Exception {
        Path source = project(home);
        Path nodeModules = Files.createDirectories(source.resolve("node_modules"));
        Files.writeString(nodeModules.resolve("package.json"), "{}");
        Path target = home.resolve("copy");

        try (UnreadableDirectory ignored = UnreadableDirectory.denyOrSkip(nodeModules)) {
            ProviderWorkspaceFiles.copyWorkspace(source, target, BUDGET, "test workspace");

            assertTrue(Files.isRegularFile(target.resolve("src/Main.java")));
        }
    }

    @Test
    void anUnreadableDirectoryThatIsNotIgnoredFailsActionablyWithTheSameType(@TempDir Path home) throws Exception {
        Path source = project(home);
        Path database = Files.createDirectories(source.resolve("data/db"));

        try (UnreadableDirectory ignored = UnreadableDirectory.denyOrSkip(database)) {
            IOException failure = assertThrows(AccessDeniedException.class, () -> ProviderWorkspaceFiles.copyWorkspace(
                    source, home.resolve("copy"), BUDGET, "test workspace"));

            assertTrue(failure.getMessage().contains("data/db"), failure.getMessage());
            assertTrue(failure.getMessage().contains(".minosignore"), failure.getMessage());
            assertFalse(failure.getMessage().contains(source.toString()), failure.getMessage());
        }
    }

    @Test
    void anUnreadableSourceRootStillFails(@TempDir Path home) throws Exception {
        Path source = project(home);

        try (UnreadableDirectory ignored = UnreadableDirectory.denyOrSkip(source)) {
            assertThrows(IOException.class, () -> ProviderWorkspaceFiles.copyWorkspace(
                    source, home.resolve("copy"), BUDGET, "test workspace"));
        }
    }

    @Test
    void theFirstRuleOfAGitignoreWithAByteOrderMarkAppliesToTheCopy(@TempDir Path home) throws Exception {
        Path source = project(home);
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] rules = "generated/\n".getBytes(StandardCharsets.UTF_8);
        byte[] content = new byte[bom.length + rules.length];
        System.arraycopy(bom, 0, content, 0, bom.length);
        System.arraycopy(rules, 0, content, bom.length, rules.length);
        Files.write(source.resolve(".gitignore"), content);
        Files.createDirectories(source.resolve("generated"));
        Files.writeString(source.resolve("generated/Big.java"), "class Big {}");
        Path target = home.resolve("copy");

        ProviderWorkspaceFiles.copyWorkspace(source, target, BUDGET, "test workspace");

        assertTrue(Files.isRegularFile(target.resolve("src/Main.java")));
        assertFalse(Files.exists(target.resolve("generated/Big.java")),
                "the first rule of the file is the one the BOM used to hide");
    }

    @Test
    void theEndToEndWorkspaceIsCreatedWithAnIgnoredUnreadableDirectoryAndLeavesNoResidueOnFailure(@TempDir Path home)
            throws Exception {
        Path registered = project(home);
        Files.writeString(registered.resolve(".gitignore"), "pgdata/\n");
        Path pgdata = Files.createDirectories(registered.resolve("pgdata"));
        Path database = Files.createDirectories(registered.resolve("data/db"));
        Path minosHome = home.resolve("minos-home");

        try (UnreadableDirectory ignoredOne = UnreadableDirectory.denyOrSkip(pgdata)) {
            IndexingExecutionRequest request = request(registered);
            try (LocalProviderWorkspace workspace = LocalProviderWorkspace.create(minosHome, request)) {
                assertTrue(Files.isRegularFile(workspace.workspaceRoot().resolve("src/Main.java")));
                assertFalse(Files.exists(workspace.workspaceRoot().resolve("pgdata")));
            }
        }

        try (UnreadableDirectory ignoredTwo = UnreadableDirectory.denyOrSkip(database)) {
            IndexingExecutionRequest request = request(registered);
            assertThrows(AccessDeniedException.class, () -> LocalProviderWorkspace.create(minosHome, request));

            Path providerRoot = minosHome.resolve("local-provider-workspaces")
                    .resolve(request.runId().toString()).resolve("fake-provider");
            assertFalse(Files.exists(providerRoot), "a failed copy leaves no partial workspace");
        }
    }

    private static Path project(Path home) throws IOException {
        Path source = Files.createDirectories(home.resolve("source"));
        Files.createDirectories(source.resolve("src"));
        Files.writeString(source.resolve("src/Main.java"), "class Main {}\n");
        return source;
    }

    private static IndexingExecutionRequest request(Path registered) {
        IndexerDescriptor descriptor = new IndexerDescriptor(
                "fake-provider", "1", "fake", Set.of(Language.JAVA), Set.of(), Set.of(),
                IndexerQualification.QUALIFIED, 1, List.of());
        return new IndexingExecutionRequest(
                UUID.randomUUID(), UUID.randomUUID(), registered, registered, Path.of(""),
                new IndexerSelection(Language.JAVA, descriptor), IndexingMode.FULL, List.of());
    }
}
