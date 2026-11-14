package com.minos.cli;

import com.minos.application.MinosApplication;
import com.minos.application.semantic.LocalHashEmbeddingProvider;
import com.minos.cli.command.SemanticProjectionCommand;
import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.PositionEncoding;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.domain.SymbolLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code minos semantic projection prepare|status} on a real home whose index was built by the deterministic
 * local-hash provider: the preparation publishes one file, twice the same, and the status reads it back.
 */
class SemanticProjectionCommandLineTest {

    private static final String PROJECT = "projection-fixture";

    @TempDir
    Path temp;

    private record Run(int exit, String output, String error) { }

    @Test
    void thePreparationPublishesTheSameFileTwiceAndTheStatusReadsItBack() throws Exception {
        try (MinosApplication application = indexedApplication(true)) {
            String projectId = application.projectRegistry().listProjects().getFirst().id().toString();
            Path file = temp.resolve("home").resolve("semantic-projections").resolve(projectId)
                    .resolve("projection-v1.bin");

            Run absent = run(application, "semantic", "projection", "status", PROJECT, "--format", "json");
            assertFalse(Files.exists(file.getParent().getParent()), "reading a status creates nothing");
            Run first = run(application, "semantic", "projection", "prepare", PROJECT, "--seed", "7",
                    "--kinds", "chunk,SYMBOL", "--format", "json");
            byte[] published = Files.readAllBytes(file);
            Run second = run(application, "semantic", "projection", "prepare", PROJECT, "--seed", "7",
                    "--kinds", "SYMBOL,CHUNK", "--max-memory-mb", "64");
            Run ready = run(application, "semantic", "projection", "status", PROJECT, "--format", "json");
            Run readyText = run(application, "semantic", "projection", "status", PROJECT);

            assertEquals(0, absent.exit(), absent.error());
            assertTrue(absent.output().contains("\"state\":\"ABSENTE\""), absent.output());
            assertTrue(absent.output().contains("\"prepareCommand\":\"minos semantic projection prepare " + projectId),
                    absent.output());
            assertEquals(0, first.exit(), first.error());
            assertTrue(first.output().contains("\"state\":\"PRETE\""), first.output());
            assertTrue(first.output().contains("\"seed\":7"), first.output());
            assertTrue(first.output().contains("\"sampled\":false"), first.output());
            assertTrue(first.error().contains("projection: pass 1 (select)"), first.error());
            assertTrue(first.error().contains("projection: published in "), first.error());
            assertFalse(first.error().contains(temp.toString()), "no absolute path on the error stream");
            assertFalse(first.output().contains(temp.toString()), "no absolute path in the report");
            assertEquals(0, second.exit(), second.error());
            assertTrue(second.output().contains("state: PRETE"), second.output());
            assertArrayEquals(published, Files.readAllBytes(file), "two identical preparations, one identical file");
            assertTrue(ready.output().contains("\"state\":\"PRETE\""), ready.output());
            assertTrue(ready.output().contains("\"method\":\"pca\""), ready.output());
            assertTrue(ready.output().contains("\"kinds\":[\"CHUNK\",\"SYMBOL\"]"), ready.output());
            assertTrue(ready.output().contains("\"providerId\":\"minos-local-hash\""), ready.output());
            assertTrue(readyText.output().contains("state: PRETE"), readyText.output());
            assertTrue(readyText.output().contains("seed: 7"), readyText.output());
        }
    }

    @Test
    void anIndexThatIsNotReadyOrAnUnknownProjectIsAnExecutionErrorThatSaysWhatToDo() throws Exception {
        try (MinosApplication application = indexedApplication(false)) {
            Run notReady = run(application, "semantic", "projection", "prepare", PROJECT);
            Run unknown = run(application, "semantic", "projection", "status", "no-such-project");

            assertEquals(1, notReady.exit());
            assertTrue(notReady.error().startsWith("error: semantic projection prepare failed: "), notReady.error());
            assertTrue(notReady.error().contains("minos ide semantic-index-sync"), notReady.error());
            assertEquals("", notReady.output());
            assertEquals(1, unknown.exit());
            assertTrue(unknown.error().startsWith("error: semantic projection status failed: "), unknown.error());
            assertFalse(Files.exists(temp.resolve("home").resolve("semantic-projections")));
        }
    }

    @Test
    void aUsageErrorNamesWhatToCorrectAndNeverAsksForTheService() throws Exception {
        List<String[]> invalid = List.of(
                new String[]{},
                new String[]{"compute", PROJECT},
                new String[]{"prepare"},
                new String[]{"prepare", PROJECT, "--max-points", "0"},
                new String[]{"prepare", PROJECT, "--max-memory-mb", "0"},
                new String[]{"prepare", PROJECT, "--kinds", "CHUNK,NOPE"},
                new String[]{"prepare", PROJECT, "--kinds", "CHUNK,"},
                new String[]{"prepare", PROJECT, "--unknown"},
                new String[]{"prepare", PROJECT, "--format", "xml"},
                new String[]{"status", PROJECT, "--seed", "1"},
                new String[]{"status", PROJECT, "extra"});
        SemanticProjectionCommand command = new SemanticProjectionCommand(() -> {
            throw new AssertionError("a usage error must not reach the service");
        });

        for (String[] arguments : invalid) {
            StringBuilder output = new StringBuilder();
            StringBuilder error = new StringBuilder();
            assertEquals(2, command.run(arguments, output, error), String.join(" ", arguments));
            assertTrue(error.toString().startsWith("error: "), error.toString());
            assertTrue(error.toString().contains("Usage: minos semantic projection prepare <project>"));
            assertEquals("", output.toString());
        }
    }

    @Test
    void helpIsAnsweredForTheProjectionAndListedWithTheSemanticCommand() throws Exception {
        StringBuilder direct = new StringBuilder();
        int exit = new SemanticProjectionCommand(() -> {
            throw new AssertionError("help must not reach the service");
        }).run(new String[]{"prepare", "-h"}, direct, new StringBuilder());
        StringBuilder stateless = new StringBuilder();
        int statelessExit = MinosCliRunner.runStatelessHelp(new String[]{"semantic", "projection", "--help"},
                stateless, new StringBuilder());
        StringBuilder hybrid = new StringBuilder();
        MinosCliRunner.runStatelessHelp(new String[]{"hybrid", "--help"}, hybrid, new StringBuilder());

        assertEquals(0, exit);
        assertEquals(SemanticProjectionCommand.USAGE + "\n", direct.toString());
        assertEquals(0, statelessExit);
        assertTrue(stateless.toString().contains("Usage: minos semantic status <project>"), stateless.toString());
        assertTrue(stateless.toString().contains("minos semantic projection status <project>"), stateless.toString());
        assertFalse(hybrid.toString().contains("projection"), hybrid.toString());
        assertTrue(MinosCliRunner.isSemanticProjectionRequest(new String[]{"semantic", "projection"}));
        assertFalse(MinosCliRunner.isSemanticProjectionRequest(new String[]{"semantic", "status", "p"}));
        assertFalse(MinosCliRunner.isSemanticProjectionRequest(new String[]{"hybrid", "projection"}));
    }

    private static Run run(MinosApplication application, String... arguments) throws IOException {
        StringBuilder output = new StringBuilder();
        StringBuilder error = new StringBuilder();
        int exit = MinosCliRunner.run(application, arguments, output, error);
        return new Run(exit, output.toString(), error.toString());
    }

    /** A home with one project, an active snapshot and, when {@code withIndex}, a ready local-hash index. */
    private MinosApplication indexedApplication(boolean withIndex) throws IOException {
        Path home = temp.resolve("home");
        Path projectRoot = Files.createDirectories(temp.resolve("project").resolve("src")).getParent();
        List<Symbol> symbols = new ArrayList<>();
        String projectId;
        try (MinosApplication baseline = MinosApplication.open(home)) {
            var project = baseline.projectRegistry().registerProject(projectRoot, PROJECT);
            projectId = project.id().toString();
            for (int number = 0; number < 12; number++) {
                String name = "Service" + number;
                Files.writeString(projectRoot.resolve("src/" + name + ".java"),
                        "package fixture;\n\nclass " + name + " {\n    int value() { return " + number + "; }\n}\n",
                        StandardCharsets.UTF_8);
                symbols.add(symbol(projectId, name, "src/" + name + ".java"));
            }
            baseline.snapshotStore().publish(project.id(), "snapshot-1", symbols, List.of(), List.of());
        }
        if (!withIndex) return MinosApplication.open(home);
        MinosApplication application = MinosApplication.builder(home)
                .embeddingProvider(new LocalHashEmbeddingProvider(64)).build();
        application.semanticIndexService().synchronize(PROJECT);
        return application;
    }

    private static Symbol symbol(String projectId, String name, String fileId) {
        return new Symbol(name, "fixture:" + name, SymbolIdentityQuality.CANONICAL, projectId,
                "module-a", fileId, null, SymbolKind.CLASS, name, "fixture." + name, "class " + name, "java",
                new SymbolLocation(fileId, 3, 0, 5, 1, PositionEncoding.UTF16_CODE_UNITS),
                ResolutionStatus.RESOLVED, new Origin("fixture", "TEST", "1", "run-1", OriginType.OTHER),
                false, false, Set.of());
    }
}
