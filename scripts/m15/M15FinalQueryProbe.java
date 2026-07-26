import com.minos.cli.LocalProjectSymbolQuery;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolSearchCriteria;
import com.minos.registry.LocalProjectRegistry;
import com.minos.registry.RegisteredProject;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.FileSymbolSnapshotStore;
import com.minos.store.SnapshotQueryView;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Exact-head M15 final probe for active-snapshot caching and query-index construction. */
public final class M15FinalQueryProbe {
    private M15FinalQueryProbe() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException(
                    "usage: M15FinalQueryProbe <minos-home> <project> <repetitions> <output-json>");
        }

        Path home = Path.of(args[0]).toAbsolutePath().normalize();
        String projectIdentifier = args[1];
        int repetitions = Integer.parseInt(args[2]);
        Path output = Path.of(args[3]).toAbsolutePath().normalize();
        if (repetitions < 1) {
            throw new IllegalArgumentException("repetitions must be >= 1");
        }

        LocalProjectRegistry registry = new LocalProjectRegistry(home.resolve("registry"));
        RegisteredProject project = resolveProject(registry, projectIdentifier);

        // A separate metadata store discovers stable fixture data without polluting measured cache counters.
        FileSymbolSnapshotStore metadataStore = new FileSymbolSnapshotStore(home.resolve("symbol-snapshots"));
        CodeKnowledgeSnapshot metadata = metadataStore.loadActiveKnowledge(project.id())
                .orElseThrow(() -> new IllegalStateException("project has no active snapshot: " + project.id()));
        String queryText = metadata.symbols().stream()
                .map(Symbol::name)
                .filter(name -> name != null && !name.isBlank())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("active snapshot has no queryable symbol name"));

        FileSymbolSnapshotStore measuredStore = new FileSymbolSnapshotStore(home.resolve("symbol-snapshots"));
        LocalProjectSymbolQuery query = new LocalProjectSymbolQuery(registry, measuredStore);
        SymbolSearchCriteria criteria = SymbolSearchCriteria.lexical(queryText, 100);

        System.gc();
        Thread.sleep(50L);
        long heapBeforeBytes = usedHeapBytes();

        long firstStart = System.nanoTime();
        int firstResultCount = query.findSymbols(project.id().toString(), criteria).size();
        double firstLatencyMs = nanosToMillis(System.nanoTime() - firstStart);
        long heapAfterLoadBytes = usedHeapBytes();

        List<Double> repeatedLatenciesMs = new ArrayList<>(repetitions);
        int repeatedResultCount = -1;
        for (int index = 0; index < repetitions; index++) {
            long start = System.nanoTime();
            int resultCount = query.findSymbols(project.id().toString(), criteria).size();
            repeatedLatenciesMs.add(nanosToMillis(System.nanoTime() - start));
            if (repeatedResultCount < 0) {
                repeatedResultCount = resultCount;
            } else if (repeatedResultCount != resultCount) {
                throw new IllegalStateException("repeated query result count changed during final capture");
            }
        }

        if (firstResultCount == 0 || repeatedResultCount != firstResultCount) {
            throw new IllegalStateException(
                    "final query must be stable and non-empty: first=" + firstResultCount
                            + " repeated=" + repeatedResultCount);
        }

        SnapshotQueryView view = measuredStore.loadActiveQueryView(project.id()).orElseThrow();
        FileSymbolSnapshotStore.CacheStats cache = measuredStore.cacheStats();
        var indexes = view.indexMetrics();

        if (cache.fullSnapshotLoads() != 1L) {
            throw new IllegalStateException("expected exactly one full snapshot load, got " + cache.fullSnapshotLoads());
        }
        if (cache.queryViewBuilds() != 1L) {
            throw new IllegalStateException("expected exactly one query-view build, got " + cache.queryViewBuilds());
        }
        if (cache.entries() < 1 || cache.entries() > cache.maximumEntries()) {
            throw new IllegalStateException(
                    "cache entry bound violated: entries=" + cache.entries() + " max=" + cache.maximumEntries());
        }
        if (indexes.symbolIdEntries() != metadata.symbols().size()) {
            throw new IllegalStateException(
                    "symbol index cardinality mismatch: expected=" + metadata.symbols().size()
                            + " actual=" + indexes.symbolIdEntries());
        }

        List<Double> sorted = new ArrayList<>(repeatedLatenciesMs);
        Collections.sort(sorted);
        double repeatedAverageMs = repeatedLatenciesMs.stream().mapToDouble(Double::doubleValue).average().orElse(0.0d);
        double repeatedP50Ms = percentile(sorted, 0.50d);
        double repeatedP95Ms = percentile(sorted, 0.95d);

        String json = "{\n"
                + "  \"schemaVersion\": 1,\n"
                + "  \"milestone\": \"M15-FINAL\",\n"
                + "  \"projectId\": \"" + json(project.id().toString()) + "\",\n"
                + "  \"projectName\": \"" + json(project.displayName()) + "\",\n"
                + "  \"snapshotId\": \"" + json(view.snapshot().snapshotId()) + "\",\n"
                + "  \"queryText\": \"" + json(queryText) + "\",\n"
                + "  \"queryResultCount\": " + firstResultCount + ",\n"
                + "  \"queryInvocations\": " + (1 + repetitions) + ",\n"
                + "  \"full_snapshot_load_count\": " + cache.fullSnapshotLoads() + ",\n"
                + "  \"query_view_build_count\": " + cache.queryViewBuilds() + ",\n"
                + "  \"cache_hits\": " + cache.hits() + ",\n"
                + "  \"cache_misses\": " + cache.misses() + ",\n"
                + "  \"cache_entries\": " + cache.entries() + ",\n"
                + "  \"cache_maximum_entries\": " + cache.maximumEntries() + ",\n"
                + "  \"first_query_latency_ms\": " + decimal(firstLatencyMs) + ",\n"
                + "  \"repeated_query_latency_average_ms\": " + decimal(repeatedAverageMs) + ",\n"
                + "  \"repeated_query_latency_p50_ms\": " + decimal(repeatedP50Ms) + ",\n"
                + "  \"repeated_query_latency_p95_ms\": " + decimal(repeatedP95Ms) + ",\n"
                + "  \"query_view_build_ms\": " + decimal(nanosToMillis(view.buildNanos())) + ",\n"
                + "  \"heap_before_bytes\": " + heapBeforeBytes + ",\n"
                + "  \"heap_after_load_bytes\": " + heapAfterLoadBytes + ",\n"
                + "  \"heap_delta_bytes\": " + Math.max(0L, heapAfterLoadBytes - heapBeforeBytes) + ",\n"
                + "  \"symbol_count\": " + metadata.symbols().size() + ",\n"
                + "  \"occurrence_count\": " + metadata.occurrences().size() + ",\n"
                + "  \"relationship_count\": " + metadata.relationships().size() + ",\n"
                + "  \"index_symbol_entries\": " + indexes.symbolIdEntries() + ",\n"
                + "  \"index_normalized_name_keys\": " + indexes.normalizedNameKeys() + ",\n"
                + "  \"index_qualified_name_keys\": " + indexes.qualifiedNameKeys() + ",\n"
                + "  \"index_file_id_keys\": " + indexes.fileIdKeys() + ",\n"
                + "  \"index_resolved_symbol_keys\": " + indexes.resolvedSymbolIdKeys() + ",\n"
                + "  \"index_source_entity_keys\": " + indexes.sourceEntityKeys() + ",\n"
                + "  \"index_target_entity_keys\": " + indexes.targetEntityKeys() + ",\n"
                + "  \"index_relationship_kind_keys\": " + indexes.relationshipKindKeys() + ",\n"
                + "  \"index_references\": " + indexes.indexReferences() + "\n"
                + "}\n";

        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(output, json, StandardCharsets.UTF_8);

        System.out.printf(Locale.ROOT,
                "M15 final query SUCCESS project=%s snapshot=%s first=%.3fms repeated-p50=%.3fms repeated-p95=%.3fms full-loads=%d builds=%d hits=%d build=%.3fms index-refs=%d heap=%d%n",
                project.displayName(), view.snapshot().snapshotId(), firstLatencyMs, repeatedP50Ms, repeatedP95Ms,
                cache.fullSnapshotLoads(), cache.queryViewBuilds(), cache.hits(), nanosToMillis(view.buildNanos()),
                indexes.indexReferences(), heapAfterLoadBytes);
    }

    private static RegisteredProject resolveProject(LocalProjectRegistry registry, String identifier) throws Exception {
        try {
            UUID id = UUID.fromString(identifier);
            return registry.findProject(id)
                    .orElseThrow(() -> new IllegalArgumentException("unknown project: " + identifier));
        } catch (IllegalArgumentException ignored) {
            List<RegisteredProject> matches = registry.listProjects().stream()
                    .filter(project -> identifier.equals(project.displayName()))
                    .toList();
            if (matches.size() != 1) {
                throw new IllegalArgumentException(
                        "expected exactly one project named '" + identifier + "', found " + matches.size());
            }
            return matches.getFirst();
        }
    }

    private static long usedHeapBytes() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0d;
    }

    private static double percentile(List<Double> sorted, double percentile) {
        if (sorted.isEmpty()) {
            return 0.0d;
        }
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }

    private static String json(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
    }
}
