import com.minos.application.MinosApplication;
import com.minos.domain.*;
import com.minos.output.DeterministicJson;
import com.minos.registry.RegisteredProject;
import com.minos.semantic.*;
import com.minos.store.FileSymbolSnapshotStore;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Deterministic M21-S8 semantic/hybrid scale probe derived from M16 STANDARD cardinalities. */
public final class M21SemanticScaleProbe {
    private static final Origin ORIGIN = new Origin("m21-s8-synthetic", "synthetic", "1", "m21-s8-benchmark", OriginType.OTHER);
    private static final int SYMBOLS_PER_FILE = 10;
    private static final int EXPECTED_CHANGED_DOCUMENTS = 3;

    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("usage: M21SemanticScaleProbe <home> <SMOKE|STANDARD> <repetitions> <output-json>");
        Path home = Path.of(args[0]).toAbsolutePath().normalize();
        Profile profile = Profile.valueOf(args[1].toUpperCase(Locale.ROOT));
        int repetitions = Integer.parseInt(args[2]);
        if (repetitions < 5 || repetitions > 50) throw new IllegalArgumentException("repetitions must be between 5 and 50");
        Path output = Path.of(args[3]).toAbsolutePath().normalize();
        Files.createDirectories(output.getParent());
        deleteRecursively(home);
        Files.createDirectories(home);

        resetHeapPeaks();
        MinosApplication app = MinosApplication.builder(home).embeddingProvider(new LocalHashEmbeddingProvider()).build();
        Path projectRoot = home.resolve("m21-s8-project");
        materialize(projectRoot, profile);
        RegisteredProject project = app.projectRegistry().registerProject(projectRoot, "m21-s8-scale");
        Dataset initialDataset = generate(project.id(), profile);
        FileSymbolSnapshotStore snapshots = app.snapshotStore();

        double initialPublishMs = timed(() -> {
            snapshots.publish(project.id(), snapshotId(profile, 1), initialDataset.symbols(), initialDataset.occurrences(), initialDataset.relationships());
            return Boolean.TRUE;
        });
        forceGc();
        long initialStarted = System.nanoTime();
        SemanticIndexService.UpdateReport initial = app.semanticIndexService().synchronize(project.id());
        double initialBuildMs = elapsedMs(initialStarted);
        require(initial.state() == SemanticIndexService.State.READY, "initial semantic index must be READY");
        require(initial.documentCount() == expectedDocuments(profile), "unexpected initial semantic document count: " + initial.documentCount());
        require(initial.embeddedCount() == initial.documentCount() && initial.reused() == 0, "initial build must embed every document exactly once");

        mutateFirstPhysicalSymbol(projectRoot);
        Dataset changedDataset = changeFirstSymbol(initialDataset);
        double changedPublishMs = timed(() -> {
            snapshots.publish(project.id(), snapshotId(profile, 2), changedDataset.symbols(), changedDataset.occurrences(), changedDataset.relationships());
            return Boolean.TRUE;
        });
        initialDataset = null;
        changedDataset = null;
        forceGc();

        long incrementalStarted = System.nanoTime();
        SemanticIndexService.UpdateReport incremental = app.semanticIndexService().synchronize(project.id());
        double incrementalMs = elapsedMs(incrementalStarted);
        require(incremental.state() == SemanticIndexService.State.READY, "incremental semantic index must be READY");
        require(incremental.documentCount() == expectedDocuments(profile), "unexpected incremental semantic document count");
        require(incremental.embeddedAdded() == 0 && incremental.removed() == 0, "controlled mutation must preserve semantic stable keys");
        require(incremental.embeddedChanged() == EXPECTED_CHANGED_DOCUMENTS,
                "single-source mutation must change exactly 3 semantic documents, actual=" + incremental.embeddedChanged());
        require(incremental.reused() == incremental.documentCount() - EXPECTED_CHANGED_DOCUMENTS,
                "unexpected semantic reuse count: " + incremental.reused());

        SemanticIndexService.Status status = app.semanticIndexService().status(project.id());
        require(status.state() == SemanticIndexService.State.READY, "semantic status must be READY");
        require(status.documentCount() == expectedDocuments(profile), "semantic status count mismatch");
        require(status.dimensions() == LocalHashEmbeddingProvider.DEFAULT_DIMENSIONS, "benchmark must use default 384 dimensions");

        String ref = project.id().toString();
        String query = "SymbolGroup0500";
        var semanticRequest = new SemanticSearchService.SearchRequest(query, 20, -1.0);
        var hybridRequest = new HybridSearchService.HybridRequest(query, 20, 0.0);
        var contextRequest = new HybridContextBuilder.ContextRequest(query, 10, 4_000, 800);

        var semanticProof = app.semanticSearchService().search(ref, semanticRequest);
        require(!semanticProof.hits().isEmpty(), "semantic probe must return hits");
        require(semanticProof.limitations().contains("VECTOR_SEARCH_LINEAR_SCAN"), "current M20 linear scan must remain explicitly observable");
        var hybridProof = app.hybridSearchService().search(ref, hybridRequest);
        require(hybridProof.semanticAvailable() && !hybridProof.hits().isEmpty(), "hybrid probe must use semantic signal and return hits");
        var contextProof = app.hybridContextBuilder().build(ref, contextRequest);
        require(!contextProof.items().isEmpty() && contextProof.usedTokens() <= contextProof.maxTokens(), "hybrid context must remain bounded and non-empty");

        Map<String, Stats> operations = new LinkedHashMap<>();
        SemanticVectorStore vectorStore = app.semanticVectorStore();
        operations.put("vector-store-load", measure(repetitions, () -> vectorStore.load(ref).orElseThrow()));
        operations.put("semantic-search", measure(repetitions, () -> app.semanticSearchService().search(ref, semanticRequest)));
        operations.put("hybrid-search", measure(repetitions, () -> app.hybridSearchService().search(ref, hybridRequest)));
        operations.put("hybrid-context", measure(repetitions, () -> app.hybridContextBuilder().build(ref, contextRequest)));

        forceGc();
        long retainedHeap = usedHeap();
        long peakHeap = peakHeap();
        long maxHeap = Runtime.getRuntime().maxMemory();
        double reuseRatio = incremental.reused() / (double) incremental.documentCount();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema_version", 1);
        result.put("profile", profile.name());
        result.put("seed", 16000031);
        result.put("logical_file_count", profile.files());
        result.put("symbol_count", profile.symbols());
        result.put("occurrence_count", profile.occurrences());
        result.put("relationship_count", profile.relationships());
        result.put("semantic_document_count", status.documentCount());
        result.put("vector_dimensions", status.dimensions());
        result.put("embedding_provider", status.providerId());
        result.put("embedding_model", status.modelId());
        result.put("initial_snapshot_publish_ms", round(initialPublishMs));
        result.put("changed_snapshot_publish_ms", round(changedPublishMs));
        result.put("initial_index_build_ms", round(initialBuildMs));
        result.put("incremental_index_rebuild_ms", round(incrementalMs));
        result.put("incremental_embedded_added", incremental.embeddedAdded());
        result.put("incremental_embedded_changed", incremental.embeddedChanged());
        result.put("incremental_removed", incremental.removed());
        result.put("incremental_reused", incremental.reused());
        result.put("incremental_reuse_ratio", round(reuseRatio));
        result.put("semantic_index_disk_size_bytes", status.indexSizeBytes());
        result.put("peak_heap_bytes", peakHeap);
        result.put("retained_heap_bytes", retainedHeap);
        result.put("max_heap_bytes", maxHeap);
        result.put("linear_vector_scan_observed", true);
        Map<String, Object> operationJson = new LinkedHashMap<>();
        operations.forEach((name, stats) -> operationJson.put(name, stats.asMap()));
        result.put("operations", operationJson);
        Files.writeString(output, DeterministicJson.render(result) + System.lineSeparator(), StandardCharsets.UTF_8);

        System.out.printf(Locale.ROOT,
                "M21 S8 semantic scale: profile=%s docs=%d dims=%d initial=%.3fms incremental=%.3fms reuse=%.6f heap=%d/%d disk=%d%n",
                profile.name(), status.documentCount(), status.dimensions(), initialBuildMs, incrementalMs, reuseRatio, peakHeap, maxHeap, status.indexSizeBytes());
        operations.forEach((name, stats) -> System.out.printf(Locale.ROOT,
                "  %s p50=%.3fms p95=%.3fms p99=%.3fms avg=%.3fms%n", name, stats.p50Ms(), stats.p95Ms(), stats.p99Ms(), stats.averageMs()));
    }

    private static Dataset generate(UUID projectId, Profile profile) {
        String project = projectId.toString();
        List<Symbol> symbols = new ArrayList<>(profile.symbols());
        for (int i = 0; i < profile.symbols(); i++) {
            int fileIndex = i % profile.files();
            int slot = i / profile.files();
            String id = symbolId(i);
            String group = symbolGroup(i);
            String file = fileId(fileIndex);
            int line = methodLine(slot);
            SymbolLocation location = new SymbolLocation(file, line, 4, line, 4 + group.length(), PositionEncoding.UTF16_CODE_UNITS);
            symbols.add(new Symbol(id, "m21s8#" + id, SymbolIdentityQuality.CANONICAL, project, "module-main", file, null,
                    SymbolKind.METHOD, group, "bench." + group, "()", "JAVA", location, ResolutionStatus.RESOLVED, ORIGIN,
                    false, false, Set.of()));
        }
        List<SymbolOccurrence> occurrences = new ArrayList<>(profile.occurrences());
        for (int i = 0; i < profile.occurrences(); i++) {
            Symbol target = symbols.get(i % profile.symbols());
            occurrences.add(new SymbolOccurrence(occurrenceId(i), project, new ResolvedSymbolReference(target.id()), target.location(),
                    Set.of(OccurrenceRole.REFERENCE), ResolutionStatus.RESOLVED, ORIGIN, Set.of()));
        }
        List<Relationship> relationships = new ArrayList<>(profile.relationships());
        for (int i = 0; i < profile.relationships(); i++) {
            int source = i % profile.symbols();
            int target = (source + 1 + (i % 17)) % profile.symbols();
            RelationshipKind kind = switch (i % 10) {
                case 0, 1 -> RelationshipKind.DEPENDS_ON;
                case 2 -> RelationshipKind.READS;
                case 3 -> RelationshipKind.WRITES;
                default -> RelationshipKind.CALLS;
            };
            relationships.add(new Relationship(relationshipId(i), project,
                    new CodeEntityRef(CodeEntityType.SYMBOL, symbolId(source)),
                    new CodeEntityRef(CodeEntityType.SYMBOL, symbolId(target)), null, kind, null,
                    ResolutionStatus.RESOLVED, InformationNature.FACTUAL, null, ORIGIN, List.of()));
        }
        return new Dataset(List.copyOf(symbols), List.copyOf(occurrences), List.copyOf(relationships));
    }

    private static Dataset changeFirstSymbol(Dataset dataset) {
        List<Symbol> symbols = new ArrayList<>(dataset.symbols());
        Symbol old = symbols.getFirst();
        symbols.set(0, new Symbol(old.id(), old.symbolKey(), old.identityQuality(), old.projectId(), old.moduleId(), old.fileId(),
                old.parentSymbolId(), old.kind(), old.name() + "Changed", old.qualifiedName() + "Changed", old.signature(), old.language(),
                old.location(), old.resolutionStatus(), old.origin(), old.external(), old.generated(), old.providerReferences()));
        return new Dataset(List.copyOf(symbols), dataset.occurrences(), dataset.relationships());
    }

    private static void materialize(Path root, Profile profile) throws IOException {
        deleteRecursively(root);
        Path source = root.resolve(Path.of("src", "main", "java", "bench"));
        Files.createDirectories(source);
        Files.writeString(root.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion><groupId>bench</groupId><artifactId>m21-s8</artifactId><version>1</version></project>\n", StandardCharsets.UTF_8);
        for (int fileIndex = 0; fileIndex < profile.files(); fileIndex++) {
            StringBuilder text = new StringBuilder("package bench;\nfinal class F%06d {\n".formatted(fileIndex));
            int currentLine = 2;
            for (int slot = 0; slot < SYMBOLS_PER_FILE; slot++) {
                int targetLine = methodLine(slot);
                while (currentLine + 1 < targetLine) { text.append('\n'); currentLine++; }
                int symbolIndex = fileIndex + slot * profile.files();
                text.append("    void ").append(symbolGroup(symbolIndex)).append("() {}\n");
                currentLine++;
            }
            text.append("}\n");
            Files.writeString(source.resolve("F%06d.java".formatted(fileIndex)), text, StandardCharsets.UTF_8);
        }
    }

    private static void mutateFirstPhysicalSymbol(Path root) throws IOException {
        Path file = root.resolve(Path.of("src", "main", "java", "bench", "F000000.java"));
        String text = Files.readString(file, StandardCharsets.UTF_8);
        if (!text.contains("void SymbolGroup0000() {}")) throw new IOException("cannot locate controlled S8 mutation anchor");
        Files.writeString(file, text.replace("void SymbolGroup0000() {}", "void SymbolGroup0000Changed() {}"), StandardCharsets.UTF_8);
    }

    private static Stats measure(int repetitions, ThrowingAction action) throws Exception {
        Objects.requireNonNull(action.run(), "warm-up operation returned null");
        long[] nanos = new long[repetitions];
        for (int i = 0; i < repetitions; i++) {
            long started = System.nanoTime();
            Objects.requireNonNull(action.run(), "benchmark operation returned null");
            nanos[i] = System.nanoTime() - started;
        }
        Arrays.sort(nanos);
        return new Stats(ms(nanos[pindex(nanos.length, .50)]), ms(nanos[pindex(nanos.length, .95)]),
                ms(nanos[pindex(nanos.length, .99)]), ms(Arrays.stream(nanos).sum() / nanos.length));
    }

    private static double timed(ThrowingAction action) throws Exception { long started = System.nanoTime(); action.run(); return elapsedMs(started); }
    private static int pindex(int size, double percentile) { return (int) Math.floor((size - 1) * percentile); }
    private static int expectedDocuments(Profile p) { return p.symbols() * 2 + p.files(); }
    private static int methodLine(int slot) { return 5 + slot * 5; }
    private static String snapshotId(Profile p, int version) { return "m21-s8-" + p.name().toLowerCase(Locale.ROOT) + "-" + version; }
    private static String symbolId(int i) { return "sym-%09d".formatted(i); }
    private static String occurrenceId(int i) { return "occ-%010d".formatted(i); }
    private static String relationshipId(int i) { return "rel-%010d".formatted(i); }
    private static String symbolGroup(int i) { return "SymbolGroup%04d".formatted(i % 1_000); }
    private static String fileId(int i) { return "src/main/java/bench/F%06d.java".formatted(i); }
    private static double elapsedMs(long started) { return ms(System.nanoTime() - started); }
    private static double ms(long nanos) { return nanos / 1_000_000.0; }
    private static double round(double v) { return Math.round(v * 1_000_000.0) / 1_000_000.0; }
    private static long usedHeap() { Runtime r = Runtime.getRuntime(); return r.totalMemory() - r.freeMemory(); }
    private static long peakHeap() { return ManagementFactory.getMemoryPoolMXBeans().stream().filter(b -> b.getType() == MemoryType.HEAP).map(b -> b.getPeakUsage()).filter(Objects::nonNull).mapToLong(u -> u.getUsed()).sum(); }
    private static void resetHeapPeaks() { ManagementFactory.getMemoryPoolMXBeans().forEach(b -> b.resetPeakUsage()); }
    private static void forceGc() throws InterruptedException { System.gc(); Thread.sleep(100); System.gc(); Thread.sleep(100); }
    private static void require(boolean ok, String message) { if (!ok) throw new IllegalStateException(message); }
    private static void deleteRecursively(Path root) throws IOException { if (!Files.exists(root)) return; try (var paths = Files.walk(root)) { paths.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException e) { throw new java.io.UncheckedIOException(e); } }); } }

    private record Dataset(List<Symbol> symbols, List<SymbolOccurrence> occurrences, List<Relationship> relationships) {}
    private record Stats(double p50Ms, double p95Ms, double p99Ms, double averageMs) {
        Map<String,Object> asMap() { Map<String,Object> m = new LinkedHashMap<>(); m.put("p50_ms", round(p50Ms)); m.put("p95_ms", round(p95Ms)); m.put("p99_ms", round(p99Ms)); m.put("average_ms", round(averageMs)); return m; }
    }
    @FunctionalInterface private interface ThrowingAction { Object run() throws Exception; }
    private enum Profile {
        SMOKE(1_000, 10_000, 50_000, 20_000), STANDARD(10_000, 100_000, 500_000, 250_000);
        final int files, symbols, occurrences, relationships;
        Profile(int files, int symbols, int occurrences, int relationships) { this.files = files; this.symbols = symbols; this.occurrences = occurrences; this.relationships = relationships; }
        int files() { return files; } int symbols() { return symbols; } int occurrences() { return occurrences; } int relationships() { return relationships; }
    }
}
