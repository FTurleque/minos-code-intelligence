package com.minos.application.semantic;

import com.minos.application.resolution.ProjectResolver;
import com.minos.application.semantic.SemanticCorpusTestDoubles.CountingReader;
import com.minos.application.semantic.SemanticCorpusTestDoubles.FixedSnapshotStore;
import com.minos.application.semantic.SemanticCorpusTestDoubles.InMemoryProjectRegistry;
import com.minos.application.semantic.SemanticCorpusTestDoubles.InMemoryVectorStore;
import com.minos.application.semantic.SemanticCorpusTestDoubles.RefusingProvider;
import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.PositionEncoding;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.domain.SymbolLocation;
import com.minos.registry.RegisteredProject;
import com.minos.semantic.SemanticDocument;
import com.minos.semantic.SemanticDocumentKind;
import com.minos.semantic.SemanticVector;
import com.minos.semantic.SemanticVectorStore;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.SnapshotDescriptor;
import com.minos.store.SnapshotReadView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Measurement of the time of one page of documents of the explorer on synthetic indexes whose files carry many
 * symbols. A filtered request that keeps nothing (an absent module) makes every page scan the bounded number of
 * documents; the slowest page is the one that describes the most file documents, each of which reads the symbols
 * of its file. It asserts no duration: it writes what it measured to {@code target/explorer-page-measurements.md}
 * of the module. Run it on purpose: {@code -Dtest=SemanticCorpusPageMeasurementTest
 * -Dminos.explorer.page.measure=true}.
 */
@EnabledIfSystemProperty(named = "minos.explorer.page.measure", matches = "true")
class SemanticCorpusPageMeasurementTest {

    private static final String SNAPSHOT = "snapshot-measure";
    private static final String ABSENT_MODULE = "absent-module";
    private static final int PAGE_WARMUPS = 3;
    private static final int PAGE_MEASURES = 15;
    private static final int WALK_MEASURES = 5;
    /** Symbols and files of morpheus-engine, docs/developer/indexing-and-storage.md. */
    private static final int REALISTIC_SYMBOLS = 60_472;
    private static final int REALISTIC_FILES = 1_488;
    private static final double REALISTIC_ZIPF_EXPONENT = 0.8;

    @Test
    void measureTheSlowestFilteredPageAndAFullWalk(@TempDir Path temp) throws Exception {
        List<String> rows = new ArrayList<>();
        rows.add("| Fixture | Files | Symbols per file (min-max) | Documents | View build (ms) "
                + "| Slowest page p50 (ms) | Slowest page p95 (ms) | findFileSymbols share of the page "
                + "| findFileSymbols calls per page | Symbols returned per page | Full walk p50 (ms) "
                + "| Pages per walk | findFileSymbols calls per walk |");
        rows.add("|---|---|---|---|---|---|---|---|---|---|---|---|---|");
        rows.add(measure(temp, "2000 x 20", uniform(2_000, 20)));
        rows.add(measure(temp, "400 x 500", uniform(400, 500)));
        rows.add(measure(temp, "40 x 5000", uniform(40, 5_000)));
        rows.add(measure(temp, "morpheus-engine (Zipf 0.8)",
                zipf(REALISTIC_FILES, REALISTIC_SYMBOLS, REALISTIC_ZIPF_EXPONENT)));
        String report = "Platform: " + System.getProperty("os.name") + " " + System.getProperty("os.arch")
                + ", Java " + Runtime.version() + ", max heap " + Runtime.getRuntime().maxMemory() + " bytes, "
                + Runtime.getRuntime().availableProcessors() + " processors\n\n"
                + "Filtered request: module `" + ABSENT_MODULE + "`, limit 50; slowest page: " + PAGE_WARMUPS
                + " warm-ups, " + PAGE_MEASURES + " measures (p95 of 15 is the maximum); full walk: "
                + WALK_MEASURES + " measures after one warm-up walk.\n\n" + String.join("\n", rows) + "\n";
        assertTrue(report.indexOf(temp.toString()) < 0, "the report names no absolute path");
        Path target = Path.of(System.getProperty("basedir", "."), "target");
        Files.createDirectories(target);
        Files.writeString(target.resolve("explorer-page-measurements.md"), report, StandardCharsets.UTF_8);
    }

    private static String measure(Path temp, String name, int[] symbolsPerFile) throws IOException {
        InMemoryProjectRegistry registry = new InMemoryProjectRegistry();
        RegisteredProject project = registry.registerProject(temp, "measure");
        String projectId = project.id().toString();
        List<Symbol> symbols = new ArrayList<>();
        List<SemanticDocument> documents = new ArrayList<>();
        for (int file = 0; file < symbolsPerFile.length; file++) {
            addFile(projectId, file, symbolsPerFile[file], symbols, documents);
        }
        documents.sort(Comparator.comparing(SemanticDocument::stableKey));
        CodeKnowledgeSnapshot snapshot = new CodeKnowledgeSnapshot(project.id(), SNAPSHOT, symbols, List.of(), List.of());
        SnapshotReadView indexed = SnapshotReadView.indexed(new SnapshotDescriptor(3, SNAPSHOT, "measure",
                "f".repeat(64), symbols.size(), 0, 0), snapshot);
        CountingReader reader = new CountingReader(indexed.reader());
        FixedSnapshotStore snapshots = new FixedSnapshotStore(snapshot);
        snapshots.view = CountingReader.wrap(indexed, reader);
        RefusingProvider provider = new RefusingProvider();
        provider.refuseEveryCall = true;
        double[] values = new double[SemanticCorpusTestDoubles.DIMENSIONS];
        values[0] = 1.0;
        List<SemanticVectorStore.IndexedDocument> indexedDocuments = new ArrayList<>(documents.size());
        for (SemanticDocument document : documents) {
            indexedDocuments.add(new SemanticVectorStore.IndexedDocument(document,
                    SemanticVector.fromArray(document.stableKey(), values)));
        }
        SeekingVectorStore vectors = new SeekingVectorStore(new SemanticVectorStore.IndexSnapshot(projectId, SNAPSHOT,
                provider.id(), provider.modelId(), SemanticCorpusTestDoubles.DIMENSIONS, 1L, indexedDocuments),
                documents);
        ProjectResolver resolver = new ProjectResolver(registry);
        SemanticIndexService index = new SemanticIndexService(resolver, snapshots, vectors, Optional.of(provider));
        SemanticSearchService semanticSearch = new SemanticSearchService(index);
        SemanticCorpusQueryService corpus = new SemanticCorpusQueryService(registry, resolver, snapshots, index,
                semanticSearch, new HybridSearchService(resolver, snapshots, index, semanticSearch));

        // First walk: finds the page that reads the most files, and warms the service up.
        String slowestCursor = null;
        int slowestCalls = -1;
        String cursor = null;
        int pages = 0;
        int callsPerWalk = 0;
        do {
            reader.reset();
            SemanticCorpusQueryService.DocumentsPage page = corpus.documents(projectId, filtered(cursor));
            assertEquals(List.of(), page.documents());
            pages++;
            callsPerWalk += reader.fileSymbolsCalls;
            if (reader.fileSymbolsCalls > slowestCalls) {
                slowestCalls = reader.fileSymbolsCalls;
                slowestCursor = cursor;
            }
            cursor = page.nextCursor();
        } while (cursor != null);
        assertEquals(symbolsPerFile.length, callsPerWalk, "a walk reads each file once");

        for (int warmup = 0; warmup < PAGE_WARMUPS; warmup++) corpus.documents(projectId, filtered(slowestCursor));
        long[] pageNanos = new long[PAGE_MEASURES];
        long fileSymbolsNanos = 0L;
        long returnedPerPage = 0L;
        for (int measure = 0; measure < PAGE_MEASURES; measure++) {
            reader.reset();
            long started = System.nanoTime();
            corpus.documents(projectId, filtered(slowestCursor));
            pageNanos[measure] = System.nanoTime() - started;
            fileSymbolsNanos += reader.fileSymbolsNanos;
            returnedPerPage = reader.fileSymbolsReturned;
            assertEquals(slowestCalls, reader.fileSymbolsCalls);
        }
        long[] walkNanos = new long[WALK_MEASURES];
        for (int measure = 0; measure < WALK_MEASURES; measure++) {
            long started = System.nanoTime();
            String next = null;
            do {
                next = corpus.documents(projectId, filtered(next)).nextCursor();
            } while (next != null);
            walkNanos[measure] = System.nanoTime() - started;
        }
        Arrays.sort(pageNanos);
        Arrays.sort(walkNanos);
        long pageTotal = Arrays.stream(pageNanos).sum();
        int min = Arrays.stream(symbolsPerFile).min().orElseThrow();
        int max = Arrays.stream(symbolsPerFile).max().orElseThrow();
        return String.format(Locale.ROOT, "| %s | %d | %d-%d | %d | %.1f | %.1f | %.1f | %.0f %% | %d | %d | %.1f | %d | %d |",
                name, symbolsPerFile.length, min, max, documents.size(), millis(indexed.buildNanos()),
                millis(percentile(pageNanos, 0.50)), millis(percentile(pageNanos, 0.95)),
                100.0 * fileSymbolsNanos / pageTotal, slowestCalls, returnedPerPage,
                millis(percentile(walkNanos, 0.50)), pages, callsPerWalk);
    }

    /**
     * One file and its documents, at the shape of {@link SemanticDocumentFactory}: a symbol and a chunk document
     * per symbol, a file document per file. Symbol keys run against positions, so that the order by position
     * is not the order by key.
     */
    private static void addFile(String projectId, int file, int count, List<Symbol> symbols,
                                List<SemanticDocument> documents) {
        String fileId = String.format(Locale.ROOT, "src/pkg%02d/File%05d.java", file % 50, file);
        for (int number = 0; number < count; number++) {
            int line = number + 1;
            String symbolKey = String.format(Locale.ROOT, "key-%05d-%06d", file, count - number);
            String id = "sym-" + file + "-" + number;
            String name = "member" + number;
            symbols.add(new Symbol(id, symbolKey, SymbolIdentityQuality.CANONICAL, projectId, "module-a", fileId,
                    null, SymbolKind.METHOD, name, "pkg.File" + file + "." + name, "void " + name + "()", "java",
                    new SymbolLocation(fileId, line, 4, line, 20, PositionEncoding.UTF16_CODE_UNITS),
                    ResolutionStatus.RESOLVED, new Origin("measure", "TEST", "1", "run-1", OriginType.OTHER),
                    false, false, Set.of()));
            documents.add(document(projectId, "symbol:" + symbolKey, SemanticDocumentKind.SYMBOL, id, fileId, line));
            documents.add(document(projectId, "chunk:" + symbolKey + ":" + line + ":" + line,
                    SemanticDocumentKind.CHUNK, id, fileId, line));
        }
        documents.add(document(projectId, "file:" + fileId, SemanticDocumentKind.FILE, fileId, fileId, 0));
    }

    private static SemanticDocument document(String projectId, String stableKey, SemanticDocumentKind kind,
                                             String sourceId, String fileId, int line) {
        return new SemanticDocument(stableKey, stableKey, projectId, SNAPSHOT, kind, sourceId, fileId, line, line,
                "content of " + stableKey, "chk-" + stableKey);
    }

    private static SemanticCorpusQueryService.DocumentsRequest filtered(String cursor) {
        return new SemanticCorpusQueryService.DocumentsRequest(cursor, 50, null, ABSENT_MODULE, null, null);
    }

    private static int[] uniform(int files, int symbolsPerFile) {
        int[] counts = new int[files];
        Arrays.fill(counts, symbolsPerFile);
        return counts;
    }

    /** {@code files} file sizes, decreasing as {@code 1 / rank^exponent}, at least one symbol each, summing to {@code total}. */
    private static int[] zipf(int files, int total, double exponent) {
        double[] weights = new double[files];
        double sum = 0.0;
        for (int rank = 0; rank < files; rank++) {
            weights[rank] = 1.0 / Math.pow(rank + 1.0, exponent);
            sum += weights[rank];
        }
        int[] counts = new int[files];
        int assigned = 0;
        for (int rank = 0; rank < files; rank++) {
            counts[rank] = Math.max(1, (int) Math.floor(total * weights[rank] / sum));
            assigned += counts[rank];
        }
        for (int rank = 0; assigned < total; rank = (rank + 1) % files, assigned++) counts[rank]++;
        return counts;
    }

    private static long percentile(long[] sorted, double rank) {
        int index = (int) Math.ceil(rank * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }

    private static double millis(long nanos) {
        return nanos / 1_000_000.0;
    }

    /**
     * The index of {@link InMemoryVectorStore} with pages read by seeking in documents sorted once, as the local
     * and PostgreSQL stores do. The default of the port sorts the whole index for every page it serves, a cost of
     * the test double that would hide the cost of describing documents.
     */
    private static final class SeekingVectorStore implements SemanticVectorStore {
        private final IndexSnapshot snapshot;
        private final List<SemanticDocument> sorted;
        private final List<String> keys;
        private final IndexMetadata metadata;

        SeekingVectorStore(IndexSnapshot snapshot, List<SemanticDocument> sorted) throws IOException {
            this.snapshot = snapshot;
            this.sorted = List.copyOf(sorted);
            this.keys = this.sorted.stream().map(SemanticDocument::stableKey).toList();
            this.metadata = SemanticVectorStore.super.metadata(snapshot.projectId()).orElseThrow();
        }

        @Override
        public Optional<IndexSnapshot> load(String projectId) {
            return snapshot.projectId().equals(projectId) ? Optional.of(snapshot) : Optional.empty();
        }

        @Override
        public Optional<DocumentPage> documentPage(String projectId, String afterStableKey, int limit) {
            SemanticVectorStore.requireDocumentPageBounds(afterStableKey, limit);
            if (!snapshot.projectId().equals(projectId)) return Optional.empty();
            int from = 0;
            if (afterStableKey != null) {
                int found = Collections.binarySearch(keys, afterStableKey);
                from = found >= 0 ? found + 1 : -found - 1;
            }
            int to = Math.min(sorted.size(), from + limit);
            return Optional.of(new DocumentPage(metadata, sorted.subList(from, to),
                    to < sorted.size()));
        }

        @Override
        public void replace(IndexSnapshot next) {
            throw new UnsupportedOperationException("the measured index is never replaced");
        }

        @Override
        public void delete(String projectId) {
            throw new UnsupportedOperationException("the measured index is never deleted");
        }

        @Override
        public void replaceConditionally(IndexSnapshot next, String expectedActiveSnapshotId,
                                         ActiveSnapshotIdReader activeSnapshotReader) {
            throw new UnsupportedOperationException("the measured index is never replaced");
        }
    }
}
