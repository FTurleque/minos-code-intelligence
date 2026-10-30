package com.minos.storage.local.store;

import com.minos.storage.codec.QueryViewWeightFixtures;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.IncomingRelationships;
import com.minos.store.SnapshotReadView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The weight the query cache charges for a view must not be lower than the heap the view retains:
 * the cache bound is only a memory bound if the estimate is an upper one. The view is measured with its derived
 * index of incoming relationships built (AUD-PERF-11), since a cached view keeps it as long as it lives.
 *
 * <p>Run with {@code -Dminos.queryViewWeight.print=true} to print the measures instead of asserting.</p>
 */
class QueryViewWeightTest {

    private static final String PRINT_PROPERTY = "minos.queryViewWeight.print";
    private static final int FILES = 150;

    @Test
    void estimatedWeightIsNotBelowTheHeapRetainedByTheView(@TempDir Path workspace) throws Exception {
        List<String> failures = new ArrayList<>();
        measure(workspace, "ascii", List.<String[]>of(QueryViewWeightFixtures.ASCII), failures);
        measure(workspace, "latin1", List.<String[]>of(QueryViewWeightFixtures.LATIN1), failures);
        measure(workspace, "cjk", List.<String[]>of(QueryViewWeightFixtures.CJK), failures);
        measure(workspace, "mixed", List.of(QueryViewWeightFixtures.ASCII, QueryViewWeightFixtures.LATIN1,
                QueryViewWeightFixtures.CJK), failures);
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    private static void measure(Path workspace, String label, List<String[]> vocabularies, List<String> failures)
            throws Exception {
        Path root = workspace.resolve(label);
        UUID projectId = UUID.randomUUID();
        CodeKnowledgeSnapshot snapshot = QueryViewWeightFixtures.repository(projectId, "weight", FILES, vocabularies);
        new FileSymbolSnapshotStore(root).publish(projectId, "weight", snapshot.symbols(), snapshot.occurrences(),
                snapshot.relationships());
        snapshot = null;

        FileSymbolSnapshotStore reader = new FileSymbolSnapshotStore(root);
        long before = settledHeap();
        SnapshotReadView view = reader.loadActiveReadView(projectId).orElseThrow();
        // AUD-PERF-11 : l'index dérivé des arêtes entrantes vit autant que la vue ; il est compté dans la mesure.
        IncomingRelationships edges = view.incomingRelationships();
        long retained = settledHeap() - before;
        assertNotNull(view.snapshot());
        assertTrue(edges.targetCount() > 0, "the derived index is built before the measure");

        long estimate = reader.cacheStats().weightBytes();
        long persisted = persistedBytes(root);
        String line = String.format(Locale.ROOT,
                "%s persisted=%d retained=%d estimate=%d retained/persisted=%.2f estimate/retained=%.2f",
                label, persisted, retained, estimate, retained / (double) persisted, estimate / (double) retained);
        if (Boolean.getBoolean(PRINT_PROPERTY)) {
            System.out.println("QUERY-VIEW-WEIGHT " + line);
            return;
        }
        if (estimate < retained) failures.add("estimate below retained heap: " + line);
    }

    /** Size of the one published snapshot file: by far the largest file of a fresh store. */
    private static long persistedBytes(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile).mapToLong(QueryViewWeightTest::size).max().orElseThrow();
        }
    }

    private static long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    private static long settledHeap() throws InterruptedException {
        for (int round = 0; round < 4; round++) {
            System.gc();
            Thread.sleep(150L);
        }
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }
}
