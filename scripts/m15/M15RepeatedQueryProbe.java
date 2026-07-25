import com.minos.cli.LocalProjectSymbolQuery;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolSearchCriteria;
import com.minos.registry.LocalProjectRegistry;
import com.minos.registry.RegisteredProject;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.FileSymbolSnapshotStore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * M15-S1 probe executed against the exact M14 replay state.
 *
 * <p>This is deliberately outside production sources. It records the cost of
 * the current query path before M15 introduces caching/indexes. The current
 * LocalProjectSymbolQuery implementation performs exactly one
 * loadActiveKnowledge call per measured query invocation.</p>
 */
public final class M15RepeatedQueryProbe {
    private M15RepeatedQueryProbe() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException(
                    "usage: M15RepeatedQueryProbe <minos-home> <project> <repetitions> <output-json>");
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
        FileSymbolSnapshotStore snapshotStore = new FileSymbolSnapshotStore(home.resolve("symbol-snapshots"));
        CodeKnowledgeSnapshot snapshot = snapshotStore.loadActiveKnowledge(project.id())
                .orElseThrow(() -> new IllegalStateException("project has no active snapshot: " + project.id()));

        String queryText = snapshot.symbols().stream()
                .map(Symbol::name)
                .filter(name -> name != null && !name.isBlank())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("active snapshot has no queryable symbol name"));

        LocalProjectSymbolQuery query = new LocalProjectSymbolQuery(registry, snapshotStore);
        SymbolSearchCriteria criteria = SymbolSearchCriteria.lexical(queryText, 100);

        System.gc();
        Thread.sleep(50L);

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
                throw new IllegalStateException("repeated query result count changed during baseline capture");
            }
        }

        if (firstResultCount == 0 || repeatedResultCount != firstResultCount) {
            throw new IllegalStateException(
                    "baseline query must be stable and non-empty: first=" + firstResultCount
                            + " repeated=" + repeatedResultCount);
        }

        List<Double> sorted = new ArrayList<>(repeatedLatenciesMs);
        Collections.sort(sorted);
        double repeatedAverageMs = repeatedLatenciesMs.stream().mapToDouble(Double::doubleValue).average().orElse(0.0d);
        double repeatedP50Ms = percentile(sorted, 0.50d);
        double repeatedP95Ms = percentile(sorted, 0.95d);

        // Current production path invariant captured by S1:
        // LocalProjectSymbolQuery.loadQueryStore() calls loadActiveKnowledge() once
        // for every findSymbols invocation. The direct snapshot read above is only
        // used to discover stable fixture metadata and is excluded from this count.
        int activeSnapshotLoadCount = 1 + repetitions;

        String json = "{\n"
                + "  \"schemaVersion\": 1,\n"
                + "  \"milestone\": \"M15-S1\",\n"
                + "  \"projectId\": \"" + json(project.id().toString()) + "\",\n"
                + "  \"projectName\": \"" + json(project.displayName()) + "\",\n"
                + "  \"snapshotId\": \"" + json(snapshot.snapshotId()) + "\",\n"
                + "  \"queryText\": \"" + json(queryText) + "\",\n"
                + "  \"queryResultCount\": " + firstResultCount + ",\n"
                + "  \"queryInvocations\": " + activeSnapshotLoadCount + ",\n"
                + "  \"active_snapshot_load_count\": " + activeSnapshotLoadCount + ",\n"
                + "  \"activeSnapshotLoadCountBasis\": \"one loadActiveKnowledge call per LocalProjectSymbolQuery.findSymbols invocation on the M14 path\",\n"
                + "  \"first_query_latency_ms\": " + decimal(firstLatencyMs) + ",\n"
                + "  \"repeated_query_latency_average_ms\": " + decimal(repeatedAverageMs) + ",\n"
                + "  \"repeated_query_latency_p50_ms\": " + decimal(repeatedP50Ms) + ",\n"
                + "  \"repeated_query_latency_p95_ms\": " + decimal(repeatedP95Ms) + ",\n"
                + "  \"repetitions\": " + repetitions + ",\n"
                + "  \"heap_after_load_bytes\": " + heapAfterLoadBytes + ",\n"
                + "  \"symbol_count\": " + snapshot.symbols().size() + ",\n"
                + "  \"occurrence_count\": " + snapshot.occurrences().size() + ",\n"
                + "  \"relationship_count\": " + snapshot.relationships().size() + "\n"
                + "}\n";

        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(output, json, StandardCharsets.UTF_8);

        System.out.printf(Locale.ROOT,
                "M15-S1 query baseline SUCCESS project=%s snapshot=%s symbols=%d occurrences=%d relationships=%d first=%.3fms repeated-p50=%.3fms repeated-p95=%.3fms loads=%d heap=%d%n",
                project.displayName(), snapshot.snapshotId(), snapshot.symbols().size(), snapshot.occurrences().size(),
                snapshot.relationships().size(), firstLatencyMs, repeatedP50Ms, repeatedP95Ms,
                activeSnapshotLoadCount, heapAfterLoadBytes);
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
