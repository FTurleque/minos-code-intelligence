import com.minos.application.MinosApplication;
import com.minos.domain.CodeEntityRef;
import com.minos.domain.CodeEntityType;
import com.minos.domain.InformationNature;
import com.minos.domain.OccurrenceRole;
import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.PositionEncoding;
import com.minos.domain.Relationship;
import com.minos.domain.RelationshipKind;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.ResolvedSymbolReference;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.domain.SymbolLocation;
import com.minos.domain.SymbolOccurrence;
import com.minos.output.DeterministicJson;
import com.minos.registry.RegisteredProject;
import com.minos.semantic.HybridContextBuilder;
import com.minos.semantic.HybridSearchService;
import com.minos.semantic.LocalHashEmbeddingProvider;
import com.minos.semantic.SemanticIndexService;
import com.minos.semantic.SemanticSearchService;
import com.minos.semantic.SemanticVectorStore;
import com.minos.store.FileSymbolSnapshotStore;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * M21-S8 deterministic semantic/hybrid scale benchmark derived from the M16 dataset cardinalities.
 * It intentionally exercises the current M20 implementation before any backend/vector-layout migration.
 */
public final class M21SemanticScaleBenchmark {

    private static final Origin ORIGIN = new Origin(
            "m21-s8-synthetic", "synthetic", "1", "m21-s8-benchmark", OriginType.OTHER);
    private static final int SYMBOLS_PER_FILE = 10;
    private static final int EXPECTED_CHANGED_DOCUMENTS = 3;

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 4) {
            throw new IllegalArgumentException(
                    "usage: M21SemanticScaleBenchmark <home> <SMOKE|STANDARD> <repetitions> <output-json>");
        }
        Path home = Path.of(arguments[0]).toAbsolutePath().normalize();
        Profile profile = Profile.valueOf(arguments[1].toUpperCase());
        int repetitions = Integer.parseInt(arguments[2]);
        if (repetitions < 5 || repetitions > 50) {
            throw new IllegalArgumentException("repetitions must be between 5 and 50");
        }
        Path output = Path.of(arguments[3]).toAbsolutePath().normalize();
        Files.createDirectories(output.getParent());
        deleteRecursively(home);
        Files.createDirectories(home);

        resetHeapPeaks();
        MinosApplication application = MinosApplication.builder(home)
                .embeddingProvider(new LocalHashEmbeddingProvider())
                .build();
        Path projectRoot = home.resolve("m21-s8-project");
        materializePhysicalFixture(projectRoot, profile);
        RegisteredProject project = application.projectRegistry().registerProject(projectRoot, "m21-s8-scale");
        Dataset dataset = generate(project.id(), profile);
        FileSymbolSnapshotStore snapshots = application.snapshotStore();

        String initialSnapshotId = "m21-s8-" + profile.name().toLowerCase() + "-1";
        long publishStarted = System.nanoTime();
        snapshots.publish(project.id(), initialSnapshotId, dataset.symbols(), dataset.occurrences(), dataset.relationships());
        double initialPublishMs = elapsedMs(publishStarted);

        forceGc();
        long initialBuildStarted = System.nanoTime();
        SemanticIndexService.UpdateReport initial = application.semanticIndexService().synchronize(project.id());
        double initialBuildMs = elapsedMs(initialBuildStarted);
        require(initial.state() == SemanticIndexService.State.READY, "initial semantic index must be READY");
        require(initial.documentCount() == expectedDocuments(profile),
                "unexpected initial semantic document count: " + initial.documentCount());
        require(initial.embeddedCount() == initial.documentCount(), "initial build must embed every document");
        require(initial.reused() == 0, "initial build must not report reused vectors");

        mutateFirstPhysicalSymbol(projectRoot);
        Dataset changed = changeFirstSymbol(dataset);
        String changedSnapshotId = "m21-s8-" + profile.name().toLowerCase() + "-2";
        long changedPublishStarted = System.nanoTime();
        snapshots.publish(project.id(), changedSnapshotId, changed.symbols(), changed.occurrences(), changed.relationships());
        double changedPublishMs = elapsedMs(changedPublishStarted);
        dataset = null;
        changed = null;
        forceGc();

        long incrementalStarted = System.nanoTime();
        SemanticIndexService.UpdateReport incremental = application.semanticIndexService().synchronize(project.id());
        double incrementalMs = elapsedMs(incrementalStarted);
        require(incremental.state() == SemanticIndexService.State.READY, "incremental semantic index must be READY");
        require(incremental.documentCount() == expectedDocuments(profile),
                "unexpected incremental semantic document count: " + incremental.documentCount());
        require(incremental.embeddedChanged() == EXPECTED_CHANGED_DOCUMENTS,
                "single-source mutation must change exactly 3 semantic documents, actual=" + incremental.embeddedChanged());
        require(incremental.embeddedAdded() == 0, "single-source mutation must not add semantic documents");
        require(incremental.removed() == 0, "single-source mutation must not remove semantic documents");
        require(incremental.reused() == incremental.documentCount() - EXPECTED_CHANGED_DOCUMENTS,
                "unexpected semantic vector reuse count: " + incremental.reused());

        SemanticIndexService.Status status = application.semanticIndexService().status(project.id());
        require(status.state() == SemanticIndexService.State.READY, "semantic status must be READY after incremental build");
        require(status.documentCount() == expectedDocuments(profile), "semantic status document count mismatch");
        require(status.dimensions() == LocalHashEmbeddingProvider.DEFAULT_DIMENSIONS, "unexpected semantic dimensions");

        String projectReference = project.id().toString();
        String query = "SymbolGroup0500";
        SemanticSearchService.SearchRequest semanticRequest = new SemanticSearchService.SearchRequest(query, 20, -1.0);
        HybridSearchService.HybridRequest hybridRequest = new HybridSearchService.HybridRequest(query, 20, 0.0);
        HybridContextBuilder.ContextRequest contextRequest = new HybridContextBuilder.ContextRequest(query, 10, 4_000, 800);

        var semanticProof = application.semanticSearchService().search(projectReference, semanticRequest);
        require(!semanticProof.hits().isEmpty(), "semantic benchmark query must return hits");
        require(semanticProof.limitations().contains("VECTOR_SEARCH_LINEAR_SCAN"),
                "S8 benchmark must identify the current linear vector scan");
        var hybridProof = application.hybridSearchService().search(projectReference, hybridRequest);
        require(hybridProof.semanticAvailable(), "hybrid benchmark must have semantic signal available");
        require(!hybridProof.hits().isEmpty(), "hybrid benchmark query must return hits");
        var contextProof = application.hybridContextBuilder().build(projectReference, contextRequest);
        require(!contextProof.items().isEmpty(), "hybrid context benchmark must return context items");
        require(contextProof.usedTokens() <= contextProof.maxTokens(), "hybrid context must respect token budget");

        Map<String, Stats> operations = new LinkedHashMap<>();
        SemanticVectorStore vectorStore = application.semanticVectorStore();
        operations.put("vector-store-load", measure(repetitions, () ->
                vectorStore.load(projectReference).orElseThrow()));
        operations.put("semantic-search", measure(repetitions, () ->
                application.semanticSearchService().search(projectReference, semanticRequest)));
        operations.put("hybrid-search", measure(repetitions, () ->
                application.hybridSearchService().search(projectReference, hybridRequest)));
        operations.put("hybrid-context", measure(repetitions, () ->
                application.hybridContextBuilder().build(projectReference, contextRequest)));

        forceGc();
        long retainedHeap = usedHeap();
        long peakHeap = peakHeap();
        long maxHeap = Runtime.getRuntime().maxMemory();
        long semanticDisk = status.indexSizeBytes();
        double reuseRatio = incremental.documentCount() == 0
                ? 1.0 : incremental.reused() / (double) incremental.documentCount();

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("schema_version", 1);
        json.put("profile", profile.name());
        json.put("seed", 16000031);
        json.put("logical_file_count", profile.files());
        json.put("symbol_count", profile.symbols());
        json.put("occurrence_count", profile.occurrences());
        json.put("relationship_count", profile.relationships());
        json.put("semantic_document_count", status.documentCount());
        json.put("vector_dimensions", status.dimensions());
        json.put("embedding_provider", status.providerId());
        json.put("embedding_model", status.modelId());
        json.put("initial_snapshot_publish_ms", round(initialPublishMs));
        json.put("changed_snapshot_publish_ms", round(changedPublishMs));
        json.put("initial_index_build_ms", round(initialBuildMs));
        json.put("incremental_index_rebuild_ms", round(incrementalMs));
        json.put("incremental_embedded_added", incremental.embeddedAdded());
        json.put("incremental_embedded_changed", incremental.embeddedChanged());
        json.put("incremental_removed", incremental.removed());
        json.put("incremental_reused", incremental.reused());
        json.put("incremental_reuse_ratio", round(reuseRatio));
        json.put("semantic_index_disk_size_bytes", semanticDisk);
        json.put("peak_heap_bytes", peakHeap);
        json.put("retained_heap_bytes", retainedHeap);
        json.put("max_heap_bytes", maxHeap);
        json.put("linear_vector_scan_observed", true);
        Map<String, Object> operationsJson = new LinkedHashMap<>();
        operations.forEach((name, stats) -> operationsJson.put(name, stats.asMap()));
        json.put("operations", operationsJson);
        Files.writeString(output, DeterministicJson.render(json) + System.lineSeparator(), StandardCharsets.UTF_8);

        System.out.printf(
                "M21 S8 semantic scale: profile=%s docs=%d dims=%d initial=%.3fms incremental=%.3fms reuse=%.6f heap=%d/%d disk=%d%n",
                profile.name(), status.documentCount(), status.dimensions(), initialBuildMs, incrementalMs,
                reuseRatio, peakHeap, maxHeap, semanticDisk);
        operations.forEach((name, stats) -> System.out.printf(
                "  %s p50=%.3fms p95=%.3fms p99=%.3fms avg=%.3fms%n",
                name, stats.p50Ms(), stats.p95Ms(), stats.p99Ms(), stats.averageMs()));
    }

    private static Dataset generate(UUID projectId, Profile profile) {
        String project = projectId.toString();
        List<Symbol> symbols = new ArrayList<>(profile.symbols());
        for (int index = 0; index < profile.symbols(); index++) {
            int fileIndex = index % profile.files();
            int slot = index / profile.files();
            String id = symbolId(index);
            String group = symbolGroup(index);
            String file = fileId(fileIndex);
            int line = methodLine(slot);
            SymbolLocation location = new SymbolLocation(file, line, 4, line, 4 + group.length(), PositionEncoding.UTF16_CODE_UNITS);
            symbols.add(new Symbol(
                    id,
                    "m21s8#" + id,
                    SymbolIdentityQuality.CANONICAL,
                    project,
                    "module-main",
                    file,
                    null,
                    SymbolKind.METHOD,
                    group,
                    "bench." + group,
                    "()",
                    "JAVA",
                    location,
                    ResolutionStatus.RESOLVED,
                    ORIGIN,
                    false,
                    false,
                    Set.of()));
        }

        List<SymbolOccurrence> occurrences = new ArrayList<>(profile.occurrences());
        for (int index = 0; index < profile.occurrences(); index++) {
            int target = index % profile.symbols();
            Symbol targetSymbol = symbols.get(target);
            occurrences.add(new SymbolOccurrence(
                    occurrenceId(index),
                    project,
                    new ResolvedSymbolReference(targetSymbol.id()),
                    targetSymbol.location(),
                    Set.of(OccurrenceRole.REFERENCE),
                    ResolutionStatus.RESOLVED,
                    ORIGIN,
                    Set.of()));
        }

        List<Relationship> relationships = new ArrayList<>(profile.relationships());
        for (int index = 0; index < profile.relationships(); index++) {
            int sourceIndex = index % profile.symbols();
            int targetIndex = (sourceIndex + 1 + (index % 17)) % profile.symbols();
            RelationshipKind kind = switch (index % 10) {
                case 0, 1 -> RelationshipKind.DEPENDS_ON;
                case 2 -> RelationshipKind.READS;
                case 3 -> RelationshipKind.WRITES;
                default -> RelationshipKind.CALLS;
            };
            relationships.add(new Relationship(
                    relationshipId(index),
                    project,
                    new CodeEntityRef(CodeEntityType.SYMBOL, symbolId(sourceIndex)),
                    new CodeEntityRef(CodeEntityType.SYMBOL, symbolId(targetIndex)),
                    null,
                    kind,
                    null,
                    ResolutionStatus.RESOLVED,
                    InformationNature.FACTUAL,
                    null,
                    ORIGIN,
                    List.of()));
        }
        return new Dataset(List.copyOf(symbols), List.copyOf(occurrences), List.copyOf(relationships));
    }

    private static Dataset changeFirstSymbol(Dataset dataset) {
        List<Symbol> symbols = new ArrayList<>(dataset.symbols());
        Symbol old = symbols.getFirst();
        symbols.set(0, new Symbol(
                old.id(), old.symbolKey(), old.identityQuality(), old.projectId(), old.moduleId(), old.fileId(),
                old.ownerSymbolId(), old.kind(), old.name() + "Changed", old.qualifiedName() + "Changed", old.signature(),
                old.language(), old.location(), old.resolutionStatus(), old.origin(), old.external(), old.generated(), old.tags()));
        return new Dataset(List.copyOf(symbols), dataset.occurrences(), dataset.relationships());
    }

    private static void materializePhysicalFixture(Path root, Profile profile) throws IOException {
        deleteRecursively(root);
        Path source = root.resolve(Path.of("src", "main", "java", "bench"));
        Files.createDirectories(source);
        Files.writeString(root.resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion><groupId>bench</groupId><artifactId>m21-s8</artifactId><version>1</version></project>\n",
                StandardCharsets.UTF_8);
        for (int fileIndex = 0; fileIndex < profile.files(); fileIndex++) {
            StringBuilder text = new StringBuilder();
            appendLine(text, "package bench;");
            appendLine(text, "final class F%06d {".formatted(fileIndex));
            int currentLine = 2;
            for (int slot = 0; slot < SYMBOLS_PER_FILE; slot++) {
                int targetLine = methodLine(slot);
                while (currentLine + 1 < targetLine) {
                    appendLine(text, "");
                    currentLine++;
                }
                int symbolIndex = fileIndex + slot * profile.files();
                appendLine(text, "    void " + symbolGroup(symbolIndex) + "() {}");
                currentLine++;
            }
            appendLine(text, "}");
            Files.writeString(source.resolve("F%06d.java".formatted(fileIndex)), text, StandardCharsets.UTF_8);
        }
    }

    private static void mutateFirstPhysicalSymbol(Path root) throws IOException {
        Path file = root.resolve(Path.of("src", "main", "java", "bench", "F000000.java"));
        String text = Files.readString(file, StandardCharsets.UTF_8);
        String before = "void SymbolGroup0000() {}";
        String after = "void SymbolGroup0000Changed() {}";
        if (!text.contains(before)) throw new IOException("cannot locate controlled S8 mutation anchor");
        Files.writeString(file, text.replace(before, after), StandardCharsets.UTF_8);
    }

    private static Stats measure(int repetitions, ThrowingAction action) throws Exception {
        Objects.requireNonNull(action.run(), "benchmark warm-up operation returned null");
        long[] nanos = new long[repetitions];
        for (int index = 0; index < repetitions; index++) {
            long started = System.nanoTime();
            Objects.requireNonNull(action.run(), "benchmark operation returned null");
            nanos[index] = System.nanoTime() - started;
        }
        Arrays.sort(nanos);
        return new Stats(
                nanosToMs(nanos[percentileIndex(nanos.length, 0.50)]),
                nanosToMs(nanos[percentileIndex(nanos.length, 0.95)]),
                nanosToMs(nanos[percentileIndex(nanos.length, 0.99)]),
                nanosToMs(Arrays.stream(nanos).sum() / nanos.length));
    }

    private static int expectedDocuments(Profile profile) {
        return profile.symbols() * 2 + profile.files();
    }

    private static int methodLine(int slot) {
        return 5 + slot * 5;
    }

    private static void appendLine(StringBuilder builder, String line) {
        builder.append(line).append('\n');
    }

    private static int percentileIndex(int size, double percentile) {
        return (int) Math.floor((size - 1) * percentile);
    }

    private static long peakHeap() {
        return ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(bean -> bean.getType() == MemoryType.HEAP)
                .map(MemoryPoolMXBean::getPeakUsage)
                .filter(Objects::nonNull)
                .mapToLong(usage -> usage.getUsed())
                .sum();
    }

    private static void resetHeapPeaks() {
        ManagementFactory.getMemoryPoolMXBeans().forEach(MemoryPoolMXBean::resetPeakUsage);
    }

    private static long usedHeap() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static void forceGc() throws InterruptedException {
        System.gc();
        Thread.sleep(100L);
        System.gc();
        Thread.sleep(100L);
    }

    private static long elapsedNanos(long started) {
        return System.nanoTime() - started;
    }

    private static double elapsedMs(long started) {
        return nanosToMs(elapsedNanos(started));
    }

    private static double nanosToMs(long nanos) {
        return nanos / 1_000_000.0;
    }

    private static double round(double value) {
        return Math.round(value * 1_000_000.0) / 1_000_000.0;
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new java.io.UncheckedIOException(exception);
                }
            });
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static String symbolId(int index) {
        return "sym-%09d".formatted(index);
    }

    private static String occurrenceId(int index) {
        return "occ-%010d".formatted(index);
    }

    private static String relationshipId(int index) {
        return "rel-%010d".formatted(index);
    }

    private static String symbolGroup(int index) {
        return "SymbolGroup%04d".formatted(index % 1_000);
    }

    private static String fileId(int index) {
        return "src/main/java/bench/F%06d.java".formatted(index);
    }

    private record Dataset(
            List<Symbol> symbols,
            List<SymbolOccurrence> occurrences,
            List<Relationship> relationships) {
    }

    private record Stats(double p50Ms, double p95Ms, double p99Ms, double averageMs) {
        Map<String, Object> asMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("p50_ms", round(p50Ms));
            values.put("p95_ms", round(p95Ms));
            values.put("p99_ms", round(p99Ms));
            values.put("average_ms", round(averageMs));
            return values;
        }
    }

    @FunctionalInterface
    private interface ThrowingAction {
        Object run() throws Exception;
    }

    private enum Profile {
        SMOKE(1_000, 10_000, 50_000, 20_000),
        STANDARD(10_000, 100_000, 500_000, 250_000);

        private final int files;
        private final int symbols;
        private final int occurrences;
        private final int relationships;

        Profile(int files, int symbols, int occurrences, int relationships) {
            this.files = files;
            this.symbols = symbols;
            this.occurrences = occurrences;
            this.relationships = relationships;
        }

        int files() { return files; }
        int symbols() { return symbols; }
        int occurrences() { return occurrences; }
        int relationships() { return relationships; }
    }
}
