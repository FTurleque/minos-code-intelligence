package com.minos.context;

import com.minos.cli.LocalProjectSymbolQuery;
import com.minos.domain.SymbolSearchCriteria;
import com.minos.registry.LocalProjectRegistry;
import com.minos.store.FileSymbolSnapshotStore;

import java.nio.file.Path;
import java.util.Arrays;

/**
 * Harness de latence M4 sur un snapshot local déjà publié.
 */
public final class CodeSearchBenchmark {

    private CodeSearchBenchmark() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 3) {
            throw new IllegalArgumentException(
                    "Usage: CodeSearchBenchmark <minos-home> <project> <query>"
            );
        }
        Path home = Path.of(arguments[0]).toAbsolutePath().normalize();
        LocalProjectSymbolQuery query = new LocalProjectSymbolQuery(
                new LocalProjectRegistry(home.resolve("registry")),
                new FileSymbolSnapshotStore(home.resolve("symbol-snapshots"))
        );
        CodeSearchCriteria criteria = new CodeSearchCriteria(
                SymbolSearchCriteria.lexical(arguments[2], 5),
                2,
                5,
                10,
                2,
                4_000,
                true
        );

        for (int index = 0; index < 20; index++) {
            query.searchCode(arguments[1], criteria);
        }
        long[] durations = new long[200];
        CodeSearchResponse response = null;
        for (int index = 0; index < durations.length; index++) {
            long started = System.nanoTime();
            response = query.searchCode(arguments[1], criteria);
            durations[index] = System.nanoTime() - started;
        }
        Arrays.sort(durations);

        metric("iterations", durations.length);
        metric("p50Milliseconds", millis(percentile(durations, 0.50)));
        metric("p95Milliseconds", millis(percentile(durations, 0.95)));
        metric("p99Milliseconds", millis(percentile(durations, 0.99)));
        metric("resultCount", response.count());
        metric("estimatedTokens", response.estimatedTokens());
        metric("estimatedTokensAvoided", response.estimatedTokensAvoided());
        metric("truncated", response.truncated());
    }

    private static long percentile(long[] sorted, double percentile) {
        int index = Math.max(0, (int) Math.ceil(sorted.length * percentile) - 1);
        return sorted[Math.min(index, sorted.length - 1)];
    }

    private static String millis(long nanoseconds) {
        return String.format(java.util.Locale.ROOT, "%.3f", nanoseconds / 1_000_000.0);
    }

    private static void metric(String name, Object value) {
        System.out.println("METRIC\t" + name + "\t" + value);
    }
}
