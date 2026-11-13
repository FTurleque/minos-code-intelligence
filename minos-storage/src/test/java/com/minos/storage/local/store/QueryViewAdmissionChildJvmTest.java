package com.minos.storage.local.store;

import com.minos.domain.Symbol;
import com.minos.storage.codec.PersistedSizeFixtures;
import com.minos.storage.codec.QueryViewMemoryBudget;
import com.minos.storage.codec.SnapshotCodecV3;
import com.minos.storage.codec.SnapshotLimits;
import com.minos.store.CodeKnowledgeSnapshot;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AUD-PERF-15 — two views loaded at the same instant in a JVM whose heap holds one of them but not both. Without
 * admission the child JVM dies of an {@link OutOfMemoryError} (exit code 3 under {@code -XX:+ExitOnOutOfMemoryError}),
 * which proves the fixture really overflows the heap; with the process budget, one view is loaded and the other is
 * loaded after waiting or refused by the explicit error, and the child ends with code 0.
 *
 * <p>Each snapshot holds 37 distinct ASCII strings of 4 Mi characters (148 MiB in V3, an estimated view of 185 MiB).
 * The child runs with {@code -Xmx256m -XX:+UseSerialGC} and a view budget of {@value #PERCENT} percent of its heap,
 * about 198 MiB: one view fits the budget, two decoded views (296 MiB of strings) cannot fit the heap.</p>
 */
class QueryViewAdmissionChildJvmTest {

    private static final int PERCENT = 80;
    private static final int STRINGS = 37;
    private static final int STRING_CHARS = 4 * 1024 * 1024;
    private static final int OOM_EXIT_CODE = 3;

    @TempDir
    static Path root;

    private static UUID projectA;
    private static UUID projectB;

    @BeforeAll
    static void publishTwoLargeSnapshots() throws IOException {
        projectA = UUID.randomUUID();
        projectB = UUID.randomUUID();
        FileSymbolSnapshotStore publisher = new FileSymbolSnapshotStore(root);
        for (UUID projectId : List.of(projectA, projectB)) {
            CodeKnowledgeSnapshot snapshot = distinctLargeStrings(projectId);
            long estimate = SnapshotLimits.production().amplifiedBytes(3, new SnapshotCodecV3().encodedSize(snapshot));
            assertTrue(estimate < 256L * 1024L * 1024L * PERCENT / 100L * 95L / 100L,
                    "one view must fit the child's budget: " + estimate);
            publisher.publish(projectId, "large", snapshot.symbols(), snapshot.occurrences(), snapshot.relationships());
        }
    }

    @Test
    void withoutAdmissionTwoSimultaneousLoadsExhaustTheHeap() throws Exception {
        Result result = runProbe("unbounded");

        assertEquals(OOM_EXIT_CODE, result.exitCode(), "the fixture must overflow the child heap: " + result.output());
    }

    @Test
    void withAdmissionOneViewIsLoadedAndTheOtherWaitsOrIsRefusedWithoutOutOfMemory() throws Exception {
        Result result = runProbe("bounded");

        assertEquals(0, result.exitCode(), result.output());
        List<String> lines = result.output().lines().filter(line -> line.startsWith(QueryViewAdmissionProbe.LINE)).toList();
        assertEquals(2, lines.size(), result.output());
        assertTrue(lines.stream().anyMatch(line -> line.endsWith(" loaded " + STRINGS)), result.output());
        for (String line : lines) {
            assertTrue(line.endsWith(" loaded " + STRINGS) || line.endsWith(" refused"), line);
        }
        assertTrue(!result.output().contains("OutOfMemoryError"), result.output());
    }

    private static CodeKnowledgeSnapshot distinctLargeStrings(UUID projectId) {
        String base = "s".repeat(STRING_CHARS - 16);
        List<Symbol> symbols = new ArrayList<>();
        for (int index = 0; index < STRINGS; index++) {
            // A distinct prefix per string: the decoder shares equal strings, these stay distinct on the heap.
            symbols.add(PersistedSizeFixtures.symbol(projectId, index,
                    String.format(Locale.ROOT, "%015d-", index) + base));
        }
        return new CodeKnowledgeSnapshot(projectId, "large", symbols, List.of(), List.of());
    }

    private static Result runProbe(String mode) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        ProcessBuilder builder = new ProcessBuilder(List.of(java.toString(),
                "-Xmx256m", "-XX:+UseSerialGC", "-XX:+ExitOnOutOfMemoryError",
                "-D" + QueryViewMemoryBudget.PROPERTY + "=" + PERCENT,
                "-cp", System.getProperty("java.class.path"),
                QueryViewAdmissionProbe.class.getName(), mode, root.toString(),
                projectA.toString(), projectB.toString()))
                .redirectErrorStream(true);
        builder.environment().remove(QueryViewMemoryBudget.VARIABLE);
        builder.environment().remove("JAVA_TOOL_OPTIONS");
        Process child = builder.start();
        byte[] output = child.getInputStream().readAllBytes();
        assertTrue(child.waitFor(5, TimeUnit.MINUTES), "probe timed out");
        return new Result(child.exitValue(), new String(output, StandardCharsets.UTF_8));
    }

    private record Result(int exitCode, String output) { }
}
