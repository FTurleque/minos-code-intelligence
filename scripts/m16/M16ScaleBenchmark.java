import com.minos.application.MinosApplication;
import com.minos.application.ProjectQueryService;
import com.minos.context.CodeSearchCriteria;
import com.minos.domain.CodeEntityRef;
import com.minos.domain.CodeEntityType;
import com.minos.domain.InformationNature;
import com.minos.domain.OccurrenceRole;
import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.PositionEncoding;
import com.minos.domain.Relationship;
import com.minos.domain.RelationshipKind;
import com.minos.domain.RelationshipSearchCriteria;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.ResolvedSymbolReference;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.domain.SymbolLocation;
import com.minos.domain.SymbolOccurrence;
import com.minos.domain.SymbolSearchCriteria;
import com.minos.impact.ImpactAnalysisRequest;
import com.minos.output.DeterministicJson;
import com.minos.registry.RegisteredProject;
import com.minos.store.FileSymbolSnapshotStore;
import com.minos.store.InMemoryCodeKnowledgeStore;
import com.minos.store.SnapshotQueryView;

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
import java.util.Set;
import java.util.UUID;

/** Deterministic M16 query/memory/disk benchmark executed against the exact shaded JAR under qualification. */
public final class M16ScaleBenchmark {

    private static final Origin ORIGIN = new Origin(
            "m16-synthetic", "synthetic", "1", "m16-benchmark", OriginType.OTHER);

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 4) {
            throw new IllegalArgumentException(
                    "usage: M16ScaleBenchmark <home> <SMOKE|STANDARD|EXTENDED|STRESS> <repetitions> <output-json>");
        }
        Path home = Path.of(arguments[0]).toAbsolutePath().normalize();
        Profile profile = Profile.valueOf(arguments[1].toUpperCase());
        int repetitions = Integer.parseInt(arguments[2]);
        if (repetitions < 5 || repetitions > 500) {
            throw new IllegalArgumentException("repetitions must be between 5 and 500");
        }
        Path output = Path.of(arguments[3]).toAbsolutePath().normalize();
        Files.createDirectories(output.getParent());
        deleteRecursively(home);
        Files.createDirectories(home);

        resetHeapPeaks();
        long coldStarted = System.nanoTime();
        MinosApplication application = MinosApplication.open(home);
        double coldStartMs = elapsedMs(coldStarted);

        Path projectRoot = home.resolve("m16-scale-project");
        int physicalFiles = materializePhysicalFixture(projectRoot, profile.physicalFiles());
        RegisteredProject project = application.projectRegistry().registerProject(projectRoot, "m16-scale");

        Dataset dataset = generate(project.id(), profile);
        FileSymbolSnapshotStore store = application.snapshotStore();
        long publishStarted = System.nanoTime();
        store.publish(
                project.id(),
                "m16-" + profile.name().toLowerCase(),
                dataset.symbols(),
                dataset.occurrences(),
                dataset.relationships()
        );
        double publishMs = elapsedMs(publishStarted);
        dataset = null;
        forceGc();

        long loadStarted = System.nanoTime();
        SnapshotQueryView view = store.loadActiveQueryView(project.id()).orElseThrow();
        double snapshotLoadMs = elapsedMs(loadStarted);
        double indexBuildMs = view.indexBuildNanos() / 1_000_000.0;
        InMemoryCodeKnowledgeStore.IndexMetrics indexMetrics = view.queryStore().indexMetrics();

        ProjectQueryService queries = application.projectQueryService();
        String projectName = project.displayName();
        int middle = profile.symbols() / 2;
        String symbolGroup = symbolGroup(middle);
        String usageAnchor = symbolId(middle + 3);
        String dependencyAnchor = symbolId(middle + 2);
        String dependentAnchor = symbolId(middle + 3);
        String impactAnchor = symbolId(middle + 1);
        String relatedTestAnchor = symbolId((middle / 10) * 10);

        Map<String, Stats> queryStats = new LinkedHashMap<>();
        queryStats.put("find-symbol", measure(repetitions, () -> queries.findSymbols(
                projectName, SymbolSearchCriteria.lexical(symbolGroup, 20))));
        queryStats.put("find-usages", measure(repetitions, () -> queries.findUsages(
                projectName, usageAnchor, 20)));
        queryStats.put("dependencies", measure(repetitions, () -> queries.findRelationships(
                projectName,
                RelationshipSearchCriteria.outgoing(
                        new CodeEntityRef(CodeEntityType.SYMBOL, dependencyAnchor),
                        Set.of(RelationshipKind.DEPENDS_ON),
                        20))));
        queryStats.put("dependents", measure(repetitions, () -> queries.findRelationships(
                projectName,
                RelationshipSearchCriteria.incoming(
                        new CodeEntityRef(CodeEntityType.SYMBOL, dependentAnchor),
                        Set.of(RelationshipKind.DEPENDS_ON),
                        20))));
        queryStats.put("related-tests", measure(repetitions, () -> queries.findRelationships(
                projectName,
                RelationshipSearchCriteria.any(
                        new CodeEntityRef(CodeEntityType.SYMBOL, relatedTestAnchor),
                        Set.of(RelationshipKind.RELATED_TEST),
                        20))));
        queryStats.put("search", measure(repetitions, () -> queries.searchCode(
                projectName,
                new CodeSearchCriteria(
                        SymbolSearchCriteria.lexical(symbolGroup, 5),
                        1,
                        3,
                        10,
                        0,
                        4_000,
                        false))));
        queryStats.put("architecture", measure(repetitions, () ->
                application.architectureQuery().getArchitectureIntelligence(projectName)));
        queryStats.put("impact", measure(repetitions, () ->
                application.impactQuery().analyzeImpact(
                        projectName,
                        new ImpactAnalysisRequest(impactAnchor, 4, 200))));

        forceGc();
        long retainedHeap = usedHeap();
        long peakHeap = peakHeap();
        long maxHeap = Runtime.getRuntime().maxMemory();
        long snapshotDisk = directorySize(home.resolve("symbol-snapshots").resolve(project.id().toString()));
        FileSymbolSnapshotStore.CacheStats cache = store.cacheStats();

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("profile", profile.name());
        json.put("seed", 16000031);
        json.put("logical_file_count", profile.files());
        json.put("physical_file_count", physicalFiles);
        json.put("symbol_count", profile.symbols());
        json.put("occurrence_count", profile.occurrences());
        json.put("relationship_count", profile.relationships());
        json.put("cold_start_time_ms", round(coldStartMs));
        json.put("snapshot_publish_time_ms", round(publishMs));
        json.put("snapshot_load_time_ms", round(snapshotLoadMs));
        json.put("query_index_build_time_ms", round(indexBuildMs));
        json.put("peak_heap_bytes", peakHeap);
        json.put("retained_heap_bytes", retainedHeap);
        json.put("max_heap_bytes", maxHeap);
        json.put("snapshot_disk_size_bytes", snapshotDisk);
        json.put("indexes_disk_size_bytes", 0);
        json.put("index_reference_count", indexMetrics.indexReferences());
        json.put("active_snapshot_full_loads", cache.fullSnapshotLoads());
        json.put("query_view_builds", cache.queryViewBuilds());
        json.put("query_cache_hits", cache.hits());
        json.put("query_cache_misses", cache.misses());
        Map<String, Object> queriesJson = new LinkedHashMap<>();
        queryStats.forEach((name, stats) -> queriesJson.put(name, stats.asMap()));
        json.put("queries", queriesJson);
        Files.writeString(output, DeterministicJson.render(json) + System.lineSeparator(), StandardCharsets.UTF_8);

        System.out.printf(
                "M16 scale benchmark: profile=%s files=%d symbols=%d occurrences=%d relationships=%d load=%.3fms index=%.3fms heap=%d disk=%d loads=%d builds=%d hits=%d%n",
                profile.name(), profile.files(), profile.symbols(), profile.occurrences(), profile.relationships(),
                snapshotLoadMs, indexBuildMs, retainedHeap, snapshotDisk,
                cache.fullSnapshotLoads(), cache.queryViewBuilds(), cache.hits());
        queryStats.forEach((name, stats) -> System.out.printf(
                "  %s p50=%.3fms p95=%.3fms p99=%.3fms%n",
                name, stats.p50Ms(), stats.p95Ms(), stats.p99Ms()));
    }

    private static Dataset generate(UUID projectId, Profile profile) {
        String project = projectId.toString();
        SymbolLocation[] locations = new SymbolLocation[profile.files()];
        for (int index = 0; index < locations.length; index++) {
            locations[index] = new SymbolLocation(
                    fileId(index), 1, 0, 1, 1, PositionEncoding.UTF16_CODE_UNITS);
        }

        List<Symbol> symbols = new ArrayList<>(profile.symbols());
        for (int index = 0; index < profile.symbols(); index++) {
            String id = symbolId(index);
            symbols.add(new Symbol(
                    id,
                    "m16#" + id,
                    SymbolIdentityQuality.CANONICAL,
                    project,
                    null,
                    fileId(index % profile.files()),
                    null,
                    SymbolKind.METHOD,
                    symbolGroup(index),
                    "bench." + id,
                    "()",
                    "JAVA",
                    locations[index % profile.files()],
                    ResolutionStatus.RESOLVED,
                    ORIGIN,
                    false,
                    false,
                    Set.of()
            ));
        }

        List<SymbolOccurrence> occurrences = new ArrayList<>(profile.occurrences());
        for (int index = 0; index < profile.occurrences(); index++) {
            int target = index % profile.symbols();
            occurrences.add(new SymbolOccurrence(
                    occurrenceId(index),
                    project,
                    new ResolvedSymbolReference(symbolId(target)),
                    locations[(index * 31) % profile.files()],
                    Set.of(OccurrenceRole.REFERENCE),
                    ResolutionStatus.RESOLVED,
                    ORIGIN,
                    Set.of()
            ));
        }

        List<Relationship> relationships = new ArrayList<>(profile.relationships());
        for (int index = 0; index < profile.relationships(); index++) {
            int sourceIndex = index % profile.symbols();
            int targetIndex = (sourceIndex + 1) % profile.symbols();
            RelationshipKind kind = switch (sourceIndex % 10) {
                case 0 -> RelationshipKind.RELATED_TEST;
                case 2, 3 -> RelationshipKind.DEPENDS_ON;
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
                    List.of()
            ));
        }
        return new Dataset(symbols, occurrences, relationships);
    }

    private static int materializePhysicalFixture(Path root, int count) throws IOException {
        deleteRecursively(root);
        Path source = root.resolve(Path.of("src", "main", "java", "bench"));
        Files.createDirectories(source);
        Files.writeString(
                root.resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion><groupId>bench</groupId><artifactId>m16-scale</artifactId><version>1</version></project>\n",
                StandardCharsets.UTF_8
        );
        for (int index = 0; index < count; index++) {
            Files.writeString(
                    source.resolve("F%06d.java".formatted(index)),
                    "package bench; final class F%06d {}\n".formatted(index),
                    StandardCharsets.UTF_8
            );
        }
        return count;
    }

    private static Stats measure(int repetitions, ThrowingAction action) throws Exception {
        for (int index = 0; index < 5; index++) {
            Objects.requireNonNull(action.run(), "benchmark operation returned null");
        }
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
                nanosToMs(Arrays.stream(nanos).sum() / nanos.length)
        );
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
        Thread.sleep(250L);
    }

    private static long directorySize(Path root) throws IOException {
        if (!Files.exists(root)) {
            return 0L;
        }
        try (var paths = Files.walk(root)) {
            return paths
                    .filter(Files::isRegularFile)
                    .mapToLong(path -> {
                        try {
                            return Files.size(path);
                        } catch (IOException exception) {
                            throw new java.io.UncheckedIOException(exception);
                        }
                    })
                    .sum();
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
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

    private static double elapsedMs(long started) {
        return nanosToMs(System.nanoTime() - started);
    }

    private static double nanosToMs(long nanos) {
        return nanos / 1_000_000.0;
    }

    private static double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    private record Dataset(
            List<Symbol> symbols,
            List<SymbolOccurrence> occurrences,
            List<Relationship> relationships
    ) {
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
        SMOKE(1_000, 10_000, 50_000, 20_000, 1_000),
        STANDARD(10_000, 100_000, 500_000, 250_000, 10_000),
        EXTENDED(50_000, 1_000_000, 5_000_000, 2_000_000, 20_000),
        STRESS(100_000, 1_000_000, 10_000_000, 4_000_000, 20_000);

        private final int files;
        private final int symbols;
        private final int occurrences;
        private final int relationships;
        private final int physicalFiles;

        Profile(int files, int symbols, int occurrences, int relationships, int physicalFiles) {
            this.files = files;
            this.symbols = symbols;
            this.occurrences = occurrences;
            this.relationships = relationships;
            this.physicalFiles = physicalFiles;
        }

        int files() { return files; }
        int symbols() { return symbols; }
        int occurrences() { return occurrences; }
        int relationships() { return relationships; }
        int physicalFiles() { return physicalFiles; }
    }
}
