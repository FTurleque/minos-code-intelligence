package com.minos.packaging;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AUD-PERF-04 — every weighted cache built in production is bounded by a share of the heap ({@code HeapShare}), or
 * belongs to one of the closed lists below with its reason. A cache added elsewhere fails here, by its class.
 */
class WeightedCacheInventoryTest {

    private static final String WEIGHTED_CACHE = "new WeightedLruCache<";
    private static final String HEAP_SHARE = "HeapShare.";

    /** Caches counted by the view memory budget instead of a heap share. */
    private static final Map<String, String> VIEW_BUDGET_TENANTS = Map.of(
            "minos-storage/src/main/java/com/minos/storage/local/store/FileSymbolSnapshotStore.java",
            "snapshot views, registered with QueryViewMemoryBudget",
            "minos-storage/src/main/java/com/minos/storage/postgresql/snapshot/PostgresCodeKnowledgeSnapshotStore.java",
            "snapshot views, registered with QueryViewMemoryBudget",
            "minos-storage/src/main/java/com/minos/storage/codec/BudgetedViewCache.java",
            "the view cache of both snapshot stores, counted by QueryViewMemoryBudget");
    /** Caches bounded by their number of entries alone, each entry of a fixed small size. */
    private static final Map<String, String> COUNT_BOUNDED = Map.of(
            "minos-application/src/main/java/com/minos/application/semantic/SemanticCorpusQueryService.java",
            "fingerprints of the explored projects: one SHA-256 string per project, at most 64 projects");
    /** Weighted caches that are not a WeightedLruCache and must still use their heap share. */
    private static final List<String> OTHER_WEIGHTED_CACHES = List.of(
            "minos-storage/src/main/java/com/minos/storage/local/store/FileSemanticProjectionStore.java");

    @Test
    void everyWeightedCacheOfProductionIsBoundedByAHeapShareOrListedWithItsReason() throws IOException {
        Path root = repositoryRoot();
        List<String> unbounded = new ArrayList<>();
        try (Stream<Path> modules = Files.list(root)) {
            for (Path module : modules.filter(path -> path.getFileName().toString().startsWith("minos-")).toList()) {
                Path sources = module.resolve("src/main/java");
                if (!Files.isDirectory(sources)) continue;
                try (Stream<Path> files = Files.walk(sources)) {
                    for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                        String relative = root.relativize(file).toString().replace('\\', '/');
                        String text = Files.readString(file);
                        if (!text.contains(WEIGHTED_CACHE)) continue;
                        if (text.contains(HEAP_SHARE) || VIEW_BUDGET_TENANTS.containsKey(relative)
                                || COUNT_BOUNDED.containsKey(relative)) {
                            continue;
                        }
                        unbounded.add(relative);
                    }
                }
            }
        }
        for (String relative : OTHER_WEIGHTED_CACHES) {
            if (!Files.readString(root.resolve(relative)).contains(HEAP_SHARE)) unbounded.add(relative);
        }
        assertTrue(unbounded.isEmpty(), "weighted caches built without a heap share (HeapShare) and not listed with"
                + " a reason: " + unbounded);
    }

    private static Path repositoryRoot() throws IOException {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.isRegularFile(current.resolve("mvnw"))) current = current.getParent();
        if (current == null) throw new IOException("repository root not found");
        return current;
    }
}
