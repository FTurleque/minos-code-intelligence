package com.minos.bootstrap.application;

import com.minos.application.MinosApplication;
import com.minos.application.semantic.HybridSearchService;
import com.minos.application.semantic.LocalHashEmbeddingProvider;
import com.minos.application.semantic.SemanticCorpusQueryService;
import com.minos.application.semantic.SemanticIndexService;
import com.minos.application.semantic.SemanticProjectionService;
import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.PositionEncoding;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.domain.SymbolLocation;
import com.minos.program.analysis.FingerprintConstrainedJavaProgramGraphProvider;
import com.minos.program.analysis.ProgramGraphService;
import com.minos.program.analysis.RelationshipProgramGraphProvider;
import com.minos.semantic.SemanticDocument;
import com.minos.semantic.SemanticDocumentKind;
import com.minos.semantic.SemanticVectorStore;
import com.minos.store.SnapshotReadView;
import com.minos.storage.local.store.FileSemanticVectorStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Measures what the application caches retain in the heap against the weight they estimate (AUD-PERF-04, task 1.1
 * of {@code borner-les-caches-applicatifs-par-le-tas}): hybrid corpus, program graph and Java fingerprints, local
 * vector index (and the peak of its full decoding), a page of the semantic explorer, and the decoded projection per
 * point. Not run by {@code verify}: the name matches no Surefire pattern and the class needs a system property.
 *
 * <pre>
 *   ./mvnw -pl minos-bootstrap -am test -Dtest=ApplicationCacheMeasurement -Dsurefire.failIfNoSpecifiedTests=false \
 *       -Dminos.cacheMeasure.work=&lt;scratch directory&gt; \
 *       -Dminos.cacheMeasure.sourceHome=&lt;MINOS_HOME holding an indexed project&gt; \
 *       -Dminos.cacheMeasure.project=&lt;projectId&gt; -Dminos.cacheMeasure.projectionPoints=100000,1000000 \
 *       -DargLine=-Xmx1076m
 * </pre>
 *
 * <p>Other properties: {@code minos.cacheMeasure.vectorDimensions} (dimensions of the real project's local-hash
 * index, 256 by default), {@code minos.cacheMeasure.indexDocuments}, {@code .indexDimensions} and
 * {@code .indexContentChars} (synthetic index near the construction budget), {@code .projectionDimensions} and
 * {@code .preparationMegabytes} (projection).</p>
 *
 * <p>The project is copied (registry entry, snapshots, fingerprints) into a fresh home under the scratch directory:
 * the source home is only read. Retained heap is the used heap after repeated collections, taken before and after
 * each step; the snapshot view is loaded first so that it is not counted in any cache. Each step appends its lines
 * to {@code <work>/cache-measurements.txt}; nothing is written into the repository.</p>
 */
@EnabledIfSystemProperty(named = "minos.cacheMeasure.work", matches = ".+")
class ApplicationCacheMeasurement {

    private static final String[] VERBS = {"read", "write", "parse", "index", "resolve", "render", "store", "load"};
    private static final String[] NOUNS = {"symbol", "snapshot", "vector", "project", "module", "document",
            "provider", "registry", "sandbox", "relation", "occurrence", "manifest"};

    /** Dimensions of the local-hash vectors of the real project; the synchronisation budget bounds documents × it. */
    private static final int VECTOR_DIMENSIONS = Integer.getInteger("minos.cacheMeasure.vectorDimensions", 256);

    private final Path work = Path.of(System.getProperty("minos.cacheMeasure.work")).toAbsolutePath();
    private final List<String> lines = new ArrayList<>();

    @Test
    @EnabledIfSystemProperty(named = "minos.cacheMeasure.project", matches = ".+")
    void measureTheCachesOnARealProject() throws Exception {
        UUID projectId = UUID.fromString(System.getProperty("minos.cacheMeasure.project"));
        Path home = copyProject(Path.of(System.getProperty("minos.cacheMeasure.sourceHome")), projectId);
        record("jvm.maxHeap.bytes", Runtime.getRuntime().maxMemory());
        String id = projectId.toString();
        record("vector.dimensions", VECTOR_DIMENSIONS);
        try (MinosApplication application = MinosApplication.builder(home)
                .embeddingProvider(new LocalHashEmbeddingProvider(VECTOR_DIMENSIONS)).build()) {
            long started = System.nanoTime();
            SnapshotReadView view = application.snapshotStore().loadActiveReadView(projectId).orElseThrow();
            record("snapshot.view.load.millis", millis(started));
            record("snapshot.symbols", view.descriptor().symbolCount());
            record("snapshot.occurrences", view.descriptor().occurrenceCount());
            record("snapshot.relationships", view.descriptor().relationshipCount());

            measureHybrid(application, id);
            measureProgramGraph(application, id);
            if (measureVectorIndex(application, id)) measureExplorerPages(application, id);
            record("snapshot.view.kept", view.descriptor().snapshotId().length());
        } finally {
            flush();
        }
    }

    /**
     * A synthetic local index near the construction budget ({@code minos.cacheMeasure.indexDocuments} documents of
     * {@code minos.cacheMeasure.indexDimensions} dimensions and about {@code minos.cacheMeasure.indexContentChars}
     * characters each), written directly to the store, then decoded by a fresh store.
     */
    @Test
    @EnabledIfSystemProperty(named = "minos.cacheMeasure.indexDocuments", matches = ".+")
    void measureALocalIndexAtTheConstructionBudget() throws Exception {
        int documents = Integer.getInteger("minos.cacheMeasure.indexDocuments");
        int dimensions = Integer.getInteger("minos.cacheMeasure.indexDimensions", 384);
        int contentChars = Integer.getInteger("minos.cacheMeasure.indexContentChars", 1200);
        record("jvm.maxHeap.bytes", Runtime.getRuntime().maxMemory());
        String prefix = "index." + documents + "x" + dimensions + "x" + contentChars;
        try {
            Path root = Files.createDirectories(work.resolve(prefix + "-store"));
            LocalHashEmbeddingProvider provider = new LocalHashEmbeddingProvider(dimensions);
            String projectId = UUID.randomUUID().toString();
            List<SemanticVectorStore.IndexedDocument> indexed = new ArrayList<>(documents);
            StringBuilder filler = new StringBuilder();
            for (int number = 0; number < documents; number++) {
                filler.setLength(0);
                while (filler.length() < contentChars) {
                    filler.append(VERBS[(number + filler.length()) % VERBS.length]).append(' ')
                            .append(NOUNS[(number / 3 + filler.length()) % NOUNS.length]).append(number % 997).append(' ');
                }
                String key = String.format(Locale.ROOT, "chunk:s:src/%s/F%07d.java:%d", NOUNS[number % NOUNS.length],
                        number, number % 400);
                String content = filler.toString();
                indexed.add(new SemanticVectorStore.IndexedDocument(new SemanticDocument("id-" + number, key,
                        projectId, "snapshot-measure", SemanticDocumentKind.CHUNK, "s-" + number,
                        "src/F" + number + ".java", 1, 40, content, "c-" + number), provider.embed(key, content)));
            }
            new FileSemanticVectorStore(root).replace(new SemanticVectorStore.IndexSnapshot(projectId,
                    "snapshot-measure", provider.id(), provider.modelId(), dimensions, 1L, indexed));
            indexed.clear();
            try (Stream<Path> files = Files.walk(root)) {
                record(prefix + ".files.bytes", files.filter(Files::isRegularFile)
                        .mapToLong(ApplicationCacheMeasurement::size).sum());
            }
            FileSemanticVectorStore store = new FileSemanticVectorStore(root);
            long before = settledHeap();
            resetPeaks();
            long started = System.nanoTime();
            store.search(projectId, provider.embed("query", "snapshot store query"), 10, 0.0);
            record(prefix + ".search.first.millis", millis(started));
            record(prefix + ".search.first.peakAboveBefore.bytes", peakHeap() - before);
            long retained = settledHeap() - before;
            started = System.nanoTime();
            store.search(projectId, provider.embed("query", "program graph cache"), 10, 0.0);
            record(prefix + ".search.second.millis", millis(started));
            record(prefix + ".cache.estimate.bytes", store.cacheStats().weightBytes());
            record(prefix + ".cache.entries", store.cacheStats().entries());
            record(prefix + ".retained.bytes", retained);
        } finally {
            flush();
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "minos.cacheMeasure.projectionPoints", matches = ".+")
    void measureTheDecodedProjectionPerPoint() throws Exception {
        record("jvm.maxHeap.bytes", Runtime.getRuntime().maxMemory());
        try {
            for (String size : System.getProperty("minos.cacheMeasure.projectionPoints").split(",")) {
                measureProjection(Integer.parseInt(size.trim()));
            }
        } finally {
            flush();
        }
    }

    private void measureHybrid(MinosApplication application, String id) throws Exception {
        HybridSearchService service = application.hybridSearchService();
        long before = settledHeap();
        resetPeaks();
        long started = System.nanoTime();
        service.search(id, new HybridSearchService.HybridRequest("snapshot store query", 10, 0.0));
        record("hybrid.first.millis", millis(started));
        record("hybrid.first.peakAboveBefore.bytes", peakHeap() - before);
        long retained = settledHeap() - before;
        started = System.nanoTime();
        service.search(id, new HybridSearchService.HybridRequest("program graph cache", 10, 0.0));
        record("hybrid.second.millis", millis(started));
        record("hybrid.corpus.estimate.bytes", service.corpusCacheStats().weightBytes());
        record("hybrid.corpus.entries", service.corpusCacheStats().entries());
        record("hybrid.retained.bytes", retained);
    }

    private void measureProgramGraph(MinosApplication application, String id) throws Exception {
        FingerprintConstrainedJavaProgramGraphProvider fingerprints =
                new FingerprintConstrainedJavaProgramGraphProvider(application.fingerprintStore());
        ProgramGraphService service = new ProgramGraphService(application.projectRegistry(),
                application.snapshotStore(), List.of(new RelationshipProgramGraphProvider(), fingerprints));
        long before = settledHeap();
        resetPeaks();
        long started = System.nanoTime();
        var graph = service.getGraph(id);
        record("graph.first.millis", millis(started));
        record("graph.first.peakAboveBefore.bytes", peakHeap() - before);
        record("graph.nodes", graph.nodes().size());
        record("graph.edges", graph.edges().size());
        record("graph.limitations", String.join(";", graph.limitations()));
        long retained = settledHeap() - before;
        started = System.nanoTime();
        service.getGraph(id);
        record("graph.second.millis", millis(started));
        record("graph.cache.estimate.bytes", service.cacheStats().weight());
        record("graph.fingerprint.estimate.bytes", fingerprints.fingerprintCacheStats().weightBytes());
        record("graph.fingerprint.entries", fingerprints.fingerprintCacheStats().entries());
        record("graph.retained.bytes", retained);
    }

    /** Synchronises the semantic index of the project, then measures its decoding; false when it cannot be built. */
    private boolean measureVectorIndex(MinosApplication application, String id) throws Exception {
        long started = System.nanoTime();
        SemanticIndexService.UpdateReport report;
        try {
            report = application.semanticIndexService().synchronize(id);
        } catch (IOException refused) {
            record("vector.sync.refused", refused.getMessage());
            return false;
        }
        record("vector.sync.millis", millis(started));
        record("vector.sync.state", report.state().name());
        record("vector.documents", report.documentCount());
        try (Stream<Path> files = Files.walk(application.home().resolve("semantic-index"))) {
            record("vector.files.bytes", files.filter(Files::isRegularFile).mapToLong(ApplicationCacheMeasurement::size).sum());
        }
        // A fresh store over the same files: its cache is empty, so the first search decodes the whole index.
        FileSemanticVectorStore store =
                new FileSemanticVectorStore(application.home().resolve("semantic-index"));
        LocalHashEmbeddingProvider provider = new LocalHashEmbeddingProvider(VECTOR_DIMENSIONS);
        var query = provider.embed("query", "snapshot store query");
        long before = settledHeap();
        resetPeaks();
        started = System.nanoTime();
        store.search(id, query, 10, 0.0);
        record("vector.search.first.millis", millis(started));
        record("vector.search.first.peakAboveBefore.bytes", peakHeap() - before);
        long retained = settledHeap() - before;
        started = System.nanoTime();
        store.search(id, provider.embed("query", "program graph cache"), 10, 0.0);
        record("vector.search.second.millis", millis(started));
        record("vector.cache.estimate.bytes", store.cacheStats().weightBytes());
        record("vector.cache.entries", store.cacheStats().entries());
        record("vector.retained.bytes", retained);
        return true;
    }

    private void measureExplorerPages(MinosApplication application, String id) throws Exception {
        SemanticCorpusQueryService service = application.semanticCorpusQueryService();
        long before = settledHeap();
        long started = System.nanoTime();
        var first = service.documents(id, new SemanticCorpusQueryService.DocumentsRequest(null, 50, null, null, null, null));
        record("explorer.page1.millis", millis(started));
        long retained = settledHeap() - before;
        started = System.nanoTime();
        service.documents(id, new SemanticCorpusQueryService.DocumentsRequest(first.nextCursor(), 50,
                null, null, null, null));
        record("explorer.page2.millis", millis(started));
        record("explorer.retained.bytes", retained);
    }

    private void measureProjection(int points) throws Exception {
        Path home = Files.createDirectories(work.resolve("projection-" + points + "-home"));
        Path root = Files.createDirectories(work.resolve("projection-" + points + "-project"));
        int dimensions = Integer.getInteger("minos.cacheMeasure.projectionDimensions", 32);
        LocalHashEmbeddingProvider provider = new LocalHashEmbeddingProvider(dimensions);
        String snapshot = "snapshot-measure";
        String projectId;
        try (MinosApplication baseline = MinosApplication.open(home)) {
            var project = baseline.projectRegistry().registerProject(root, "measure-" + points);
            projectId = project.id().toString();
            baseline.snapshotStore().publish(project.id(), snapshot, List.of(new Symbol("Measure", "measure:Measure",
                    SymbolIdentityQuality.CANONICAL, projectId, "module-a", "src/Measure.java", null, SymbolKind.CLASS,
                    "Measure", "measure.Measure", "class Measure", "java",
                    new SymbolLocation("src/Measure.java", 1, 0, 3, 1, PositionEncoding.UTF16_CODE_UNITS),
                    ResolutionStatus.RESOLVED, new Origin("measure", "TEST", "1", "run-1", OriginType.OTHER),
                    false, false, Set.of())), List.of(), List.of());
        }
        try (MinosApplication application = MinosApplication.builder(home).embeddingProvider(provider).build()) {
            List<SemanticVectorStore.IndexedDocument> indexed = new ArrayList<>(points);
            for (int number = 0; number < points; number++) {
                String key = String.format(Locale.ROOT, "chunk:s:src/%s/F%07d.java:%d", NOUNS[number % NOUNS.length],
                        number, number % 400);
                String content = VERBS[number % VERBS.length] + " " + NOUNS[(number / 7) % NOUNS.length] + number % 97;
                indexed.add(new SemanticVectorStore.IndexedDocument(new SemanticDocument("id-" + number, key,
                        projectId, snapshot, SemanticDocumentKind.CHUNK, "s-" + number, "src/F" + number + ".java",
                        1, 3, content, "c-" + number), provider.embed(key, content)));
            }
            application.semanticVectorStore().replace(new SemanticVectorStore.IndexSnapshot(projectId, snapshot,
                    provider.id(), provider.modelId(), dimensions, 1L, indexed));
            indexed.clear();
            SemanticProjectionService service = application.semanticProjectionService();
            long started = System.nanoTime();
            var report = service.prepare(projectId, new SemanticProjectionService.PreparationRequest(
                    Math.max(points, SemanticProjectionService.DEFAULT_MAX_POINTS), SemanticProjectionService.DEFAULT_SEED,
                    Set.of(SemanticDocumentKind.CHUNK),
                    Long.getLong("minos.cacheMeasure.preparationMegabytes",
                            SemanticProjectionService.DEFAULT_MAX_MEMORY_MEGABYTES) * 1024L * 1024L),
                    SemanticProjectionService.Progress.NONE);
            record("projection." + points + ".prepare.millis", millis(started));
            record("projection." + points + ".points", report.pointCount());
            Path file = home.resolve("semantic-projections").resolve(projectId).resolve("projection-v1.bin");
            record("projection." + points + ".file.bytes", Files.size(file));
            // A second application over the same home: its projection store starts with an empty cache.
            try (MinosApplication reader = MinosApplication.builder(home).embeddingProvider(provider).build()) {
                SemanticProjectionService readService = reader.semanticProjectionService();
                long before = settledHeap();
                resetPeaks();
                started = System.nanoTime();
                readService.points(projectId, 0, SemanticProjectionService.MAX_POINTS_PAGE);
                record("projection." + points + ".firstPage.millis", millis(started));
                record("projection." + points + ".decode.peakAboveBefore.bytes", peakHeap() - before);
                long retained = settledHeap() - before;
                record("projection." + points + ".retained.bytes", retained);
                record("projection." + points + ".retained.bytesPerPoint",
                        String.format(Locale.ROOT, "%.1f", retained / (double) report.pointCount()));
                started = System.nanoTime();
                readService.points(projectId, SemanticProjectionService.MAX_POINTS_PAGE,
                        SemanticProjectionService.MAX_POINTS_PAGE);
                record("projection." + points + ".secondPage.millis", millis(started));
            }
        }
    }

    private Path copyProject(Path source, UUID projectId) throws IOException {
        Path home = work.resolve("home-" + System.currentTimeMillis());
        String id = projectId.toString();
        copyTree(source.resolve("registry").resolve("projects").resolve(id + ".properties"),
                home.resolve("registry").resolve("projects").resolve(id + ".properties"));
        copyTree(source.resolve("symbol-snapshots").resolve(id), home.resolve("symbol-snapshots").resolve(id));
        copyTree(source.resolve("fingerprint-snapshots").resolve(id), home.resolve("fingerprint-snapshots").resolve(id));
        return home;
    }

    private static void copyTree(Path source, Path target) throws IOException {
        if (!Files.exists(source)) return;
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private void record(String key, Object value) {
        String line = key + "=" + value;
        System.out.println("CACHE-MEASURE " + line);
        lines.add(line);
    }

    private void flush() throws IOException {
        Files.createDirectories(work);
        Files.write(work.resolve("cache-measurements.txt"), lines, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }

    private static long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException exception) {
            return 0L;
        }
    }

    private static long millis(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }

    private static long settledHeap() throws InterruptedException {
        for (int round = 0; round < 5; round++) {
            System.gc();
            Thread.sleep(200L);
        }
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private static void resetPeaks() {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) pool.resetPeakUsage();
    }

    private static long peakHeap() {
        long peak = 0L;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP) peak += pool.getPeakUsage().getUsed();
        }
        return peak;
    }
}
