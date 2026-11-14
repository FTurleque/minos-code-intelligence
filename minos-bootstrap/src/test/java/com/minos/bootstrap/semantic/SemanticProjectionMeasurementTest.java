package com.minos.bootstrap.semantic;

import com.minos.application.MinosApplication;
import com.minos.application.semantic.LocalHashEmbeddingProvider;
import com.minos.application.semantic.SemanticProjectionService;
import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.PositionEncoding;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.domain.SymbolLocation;
import com.minos.output.json.DeterministicJson;
import com.minos.semantic.SemanticDocument;
import com.minos.semantic.SemanticDocumentKind;
import com.minos.semantic.SemanticProjection;
import com.minos.semantic.SemanticVectorStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Measurement of the preparation and of the reading of a projection on synthetic local-hash indexes, on the
 * local store. It asserts no threshold: it writes what it measured to
 * {@code target/projection-measurements.md}. Run it on purpose:
 * {@code -Dtest=SemanticProjectionMeasurementTest -Dminos.projection.measure=true}, and
 * {@code -Dminos.projection.measure.sizes=1000,10000} to choose the corpus sizes;
 * {@code -Dminos.projection.measure.keep=<directory>} keeps the homes it built, one per size.
 */
@EnabledIfSystemProperty(named = "minos.projection.measure", matches = "true")
class SemanticProjectionMeasurementTest {

    private static final int DIMENSIONS = 256;
    private static final String SNAPSHOT = "snapshot-measure";
    private static final String[] VERBS = {"read", "write", "parse", "index", "resolve", "render", "store", "load"};
    private static final String[] NOUNS = {"symbol", "snapshot", "vector", "project", "module", "document",
            "provider", "registry", "sandbox", "relation", "occurrence", "manifest"};

    @Test
    void measureThePreparationAndTheReadingOfAProjection(@TempDir Path temp) throws Exception {
        List<String> rows = new ArrayList<>();
        rows.add("| Documents | Dimensions | Preparation (ms) | Passes | Working memory (bytes) | Heap peak (bytes) "
                + "| Cache file (bytes) | Points JSON (bytes) | Status and all points read (ms) | Explained variance |");
        rows.add("|---|---|---|---|---|---|---|---|---|---|");
        // A kept directory lets the measured homes be served afterwards, to measure the map in a browser.
        String kept = System.getProperty("minos.projection.measure.keep");
        Path base = kept == null ? temp : Files.createDirectories(Path.of(kept).toAbsolutePath());
        for (String size : System.getProperty("minos.projection.measure.sizes", "1000,10000,25000").split(",")) {
            rows.add(measure(base.resolve("home-" + size.trim()), Integer.parseInt(size.trim())));
        }
        String report = "Platform: " + System.getProperty("os.name") + " " + System.getProperty("os.arch")
                + ", Java " + Runtime.version() + ", max heap " + Runtime.getRuntime().maxMemory() + " bytes, "
                + Runtime.getRuntime().availableProcessors() + " processors\n\n" + String.join("\n", rows) + "\n";
        Files.writeString(Path.of("target", "projection-measurements.md"), report, StandardCharsets.UTF_8);
    }

    private static String measure(Path home, int documents) throws Exception {
        Path root = Files.createDirectories(home.resolveSibling(home.getFileName() + "-project"));
        LocalHashEmbeddingProvider provider = new LocalHashEmbeddingProvider(DIMENSIONS);
        String projectId;
        try (MinosApplication baseline = MinosApplication.open(home)) {
            var project = baseline.projectRegistry().registerProject(root, "measure-" + documents);
            projectId = project.id().toString();
            baseline.snapshotStore().publish(project.id(), SNAPSHOT, List.of(symbol(projectId)), List.of(), List.of());
        }
        try (MinosApplication application = MinosApplication.builder(home).embeddingProvider(provider).build()) {
            List<SemanticVectorStore.IndexedDocument> indexed = new ArrayList<>(documents);
            for (int number = 0; number < documents; number++) {
                String key = String.format(Locale.ROOT, "chunk:synthetic:src/%s/File%05d.java:%d",
                        NOUNS[number % NOUNS.length], number, number % 400);
                String content = "void " + VERBS[number % VERBS.length] + capital(NOUNS[(number / 7) % NOUNS.length])
                        + number % 97 + "() { " + NOUNS[(number / 3) % NOUNS.length] + "."
                        + VERBS[(number / 5) % VERBS.length] + "(" + NOUNS[(number / 11) % NOUNS.length] + "); }";
                indexed.add(new SemanticVectorStore.IndexedDocument(new SemanticDocument("id-" + number, key,
                        projectId, SNAPSHOT, SemanticDocumentKind.CHUNK, "source-" + number,
                        "src/File" + number + ".java", 1, 3, content, "chk-" + number),
                        provider.embed(key, content)));
            }
            application.semanticVectorStore().replace(new SemanticVectorStore.IndexSnapshot(projectId, SNAPSHOT,
                    provider.id(), provider.modelId(), DIMENSIONS, 1L, indexed));
            indexed.clear();
            System.gc();
            resetHeapPeaks();

            SemanticProjectionService service = application.semanticProjectionService();
            SemanticProjectionService.PreparationReport report = service.prepare(projectId,
                    new SemanticProjectionService.PreparationRequest(SemanticProjectionService.DEFAULT_MAX_POINTS,
                            SemanticProjectionService.DEFAULT_SEED, Set.of(SemanticDocumentKind.CHUNK),
                            SemanticProjectionService.DEFAULT_MAX_MEMORY_MEGABYTES * 1024L * 1024L),
                    SemanticProjectionService.Progress.NONE);
            long heapPeak = heapPeak();
            assertEquals(documents, report.pointCount());

            long cacheBytes = Files.size(home.resolve("semantic-projections").resolve(projectId)
                    .resolve("projection-v1.bin"));
            long started = System.nanoTime();
            service.status(projectId);
            long jsonBytes = 0L;
            int read = 0;
            while (read < documents) {
                SemanticProjectionService.Points page =
                        service.points(projectId, read, SemanticProjectionService.MAX_POINTS_PAGE);
                List<Object> points = new ArrayList<>(page.points().size());
                for (SemanticProjection.Point point : page.points()) {
                    points.add(DeterministicJson.object("stableKey", point.stableKey(), "kind", point.kind().name(),
                            "x", (double) point.x(), "y", (double) point.y()));
                }
                jsonBytes += DeterministicJson.render(DeterministicJson.object("points", points))
                        .getBytes(StandardCharsets.UTF_8).length;
                read += page.points().size();
            }
            long readMillis = (System.nanoTime() - started) / 1_000_000L;
            return "| " + documents + " | " + DIMENSIONS + " | " + report.durationMillis() + " | " + report.passes()
                    + " | " + report.peakWorkingBytes() + " | " + heapPeak + " | " + cacheBytes + " | " + jsonBytes
                    + " | " + readMillis + " | " + String.format(Locale.ROOT, "%.4f, %.4f",
                    report.explainedVariance().get(0), report.explainedVariance().get(1)) + " |";
        }
    }

    private static void resetHeapPeaks() {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) pool.resetPeakUsage();
    }

    private static long heapPeak() {
        long peak = 0L;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP) peak += pool.getPeakUsage().getUsed();
        }
        return peak;
    }

    private static String capital(String word) {
        return word.substring(0, 1).toUpperCase(Locale.ROOT) + word.substring(1);
    }

    private static Symbol symbol(String projectId) {
        return new Symbol("Measure", "measure:Measure", SymbolIdentityQuality.CANONICAL, projectId, "module-a",
                "src/Measure.java", null, SymbolKind.CLASS, "Measure", "measure.Measure", "class Measure", "java",
                new SymbolLocation("src/Measure.java", 1, 0, 3, 1, PositionEncoding.UTF16_CODE_UNITS),
                ResolutionStatus.RESOLVED, new Origin("measure", "TEST", "1", "run-1", OriginType.OTHER),
                false, false, Set.of());
    }
}
