package com.minos.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An explicit SCIP import applies the default retention on the real local store: after four imports of distinct
 * snapshots, the active one and the two most recent historical ones remain.
 */
class ImportScipRetentionTest {

    private static final Pattern PROJECT_ID = Pattern.compile("\"id\":\"([0-9a-f-]{36})\"");

    @Test
    void fourDistinctImportsKeepTheActiveSnapshotAndTwoHistoricalOnes(@TempDir Path home) throws Exception {
        Path fixture = Path.of("fixtures", "typescript", "typescript-modules");
        Path scip = fixture.resolve(Path.of(".minos-m0", "scip-typescript", "index.scip"));
        CommandResult add = run(home, "project", "add", fixture.toString(), "--name", "retention", "--format", "json");
        assertEquals(0, add.exitCode(), add.error());
        Matcher id = PROJECT_ID.matcher(add.output());
        assertTrue(id.find(), add.output());
        Path snapshots = home.resolve("symbol-snapshots").resolve(id.group(1));

        List<String> afterFirst = List.of();
        for (int index = 1; index <= 4; index++) {
            CommandResult imported = run(home, "import-scip", "retention", "--file", scip.toString(),
                    "--provider", "scip-typescript", "--snapshot", "retention-" + index, "--format", "json");
            assertEquals(0, imported.exitCode(), imported.error());
            assertTrue(imported.output().contains("\"commitStatus\":\"COMMITTED\""), imported.output());
            assertTrue(imported.output().contains("\"diagnostic\":null"), imported.output());
            if (index == 1) afterFirst = snapshotFiles(snapshots);
        }

        List<String> remaining = snapshotFiles(snapshots);
        assertEquals(1, afterFirst.size(), afterFirst.toString());
        assertEquals(3, remaining.size(), remaining.toString());
        assertFalse(remaining.contains(afterFirst.get(0)), "the oldest imported snapshot is deleted");
        CommandResult status = run(home, "index-status", "retention", "--format", "json");
        assertEquals(0, status.exitCode(), status.error());
        assertTrue(status.output().contains("\"activeSnapshotId\":\"retention-4\""), status.output());
    }

    /** The persisted knowledge snapshots of the project, as the local store names them. */
    private static List<String> snapshotFiles(Path directory) throws Exception {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("snapshot-")
                            && (name.endsWith(".knowledge") || name.endsWith(".symbols")))
                    .sorted()
                    .toList();
        }
    }

    private static CommandResult run(Path home, String... arguments) throws Exception {
        StringBuilder output = new StringBuilder();
        StringBuilder error = new StringBuilder();
        int exitCode = MinosLauncher.run(home, arguments, output, error);
        return new CommandResult(exitCode, output.toString(), error.toString());
    }

    private record CommandResult(int exitCode, String output, String error) {
    }
}
