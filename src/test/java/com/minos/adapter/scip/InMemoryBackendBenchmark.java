package com.minos.adapter.scip;

import com.minos.domain.ProviderReference;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolOccurrence;
import com.minos.query.SymbolQueryService;
import com.minos.store.InMemoryCodeKnowledgeStore;
import org.scip_code.scip.Document;
import org.scip_code.scip.Index;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Harness expérimental E1 pour mesurer le backend mémoire sur un index SCIP réel.
 *
 * <p>La classe reste dans les sources de test. Elle n'est ni une CLI produit,
 * ni une abstraction de benchmark destinée au domaine MINOS.</p>
 */
public final class InMemoryBackendBenchmark {

    private static final String DATASET_PROPERTY = "minos.m0.dataset";
    private static final String PROJECT_ID_PROPERTY = "minos.m0.projectId";
    private static final String PROVIDER_ID_PROPERTY = "minos.m0.providerId";
    private static final String PROVIDER_VERSION_PROPERTY = "minos.m0.providerVersion";
    private static final String WARMUP_ITERATIONS_PROPERTY = "minos.m0.warmupIterations";
    private static final String MEASUREMENT_ITERATIONS_PROPERTY = "minos.m0.measurementIterations";
    private static final int QUERY_LIMIT = 100;
    private static final int USAGE_LIMIT = 1_000;

    private InMemoryBackendBenchmark() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length < 2) {
            throw new IllegalArgumentException(
                    "Usage: InMemoryBackendBenchmark <index.scip> <query> [queries...]"
            );
        }

        String dataset = requiredProperty(DATASET_PROPERTY);
        String projectId = requiredProperty(PROJECT_ID_PROPERTY);
        String providerId = requiredProperty(PROVIDER_ID_PROPERTY);
        String providerVersion = requiredProperty(PROVIDER_VERSION_PROPERTY);
        int warmupIterations = positiveIntegerProperty(WARMUP_ITERATIONS_PROPERTY);
        int measurementIterations = positiveIntegerProperty(MEASUREMENT_ITERATIONS_PROPERTY);
        Path indexPath = Path.of(arguments[0]).toAbsolutePath().normalize();
        List<String> queryNames = Arrays.stream(arguments).skip(1).toList();

        resetPeakHeapUsage();
        long readStarted = System.nanoTime();
        Index index = new ScipIndexReader().read(indexPath);
        long indexReadNanos = System.nanoTime() - readStarted;
        int documentCount = index.getDocumentsCount();

        InMemoryCodeKnowledgeStore store = new InMemoryCodeKnowledgeStore();
        Map<String, String> explicitFileIds = index.getDocumentsList().stream()
                .collect(Collectors.toMap(
                        Document::getRelativePath,
                        Document::getRelativePath,
                        (first, ignored) -> first,
                        LinkedHashMap::new
                ));
        long ingestionStarted = System.nanoTime();
        ScipIngestionReport report = new ScipIngestionAdapter().ingest(
                index,
                new ScipIngestionRequest(
                        projectId,
                        "main",
                        providerId,
                        providerVersion,
                        "benchmark-" + dataset,
                        explicitFileIds
                ),
                store
        );
        long ingestionNanos = System.nanoTime() - ingestionStarted;
        long peakHeapIndexingBytes = peakHeapUsageBytes();

        index = null;
        explicitFileIds = null;
        forceGarbageCollection();
        long retainedHeapAfterIngestionBytes = usedHeapBytes();

        SymbolQueryService queryService = new SymbolQueryService(store);
        List<QueryTarget> targets = queryNames.stream()
                .map(query -> resolveTarget(queryService, projectId, query))
                .toList();

        warmUp(queryService, projectId, targets, warmupIterations);
        forceGarbageCollection();
        resetPeakHeapUsage();

        List<Long> allFindSymbolSamples = new ArrayList<>();
        List<Long> allFindUsagesSamples = new ArrayList<>();
        List<QueryMeasurement> measurements = new ArrayList<>();
        boolean allDigestsStable = true;

        for (QueryTarget target : targets) {
            QueryMeasurement findSymbol = measure(
                    "find_symbol",
                    target.query(),
                    target.symbol().id(),
                    measurementIterations,
                    () -> serializeSymbols(queryService.findSymbol(projectId, target.query(), QUERY_LIMIT))
            );
            QueryMeasurement findUsages = measure(
                    "find_usages",
                    target.query(),
                    target.symbol().id(),
                    measurementIterations,
                    () -> serializeOccurrences(
                            queryService.findUsages(projectId, target.symbol().id(), USAGE_LIMIT)
                    )
            );
            measurements.add(findSymbol);
            measurements.add(findUsages);
            allFindSymbolSamples.addAll(findSymbol.samplesNanos());
            allFindUsagesSamples.addAll(findUsages.samplesNanos());
            allDigestsStable &= findSymbol.digestStable() && findUsages.digestStable();
        }

        long peakHeapQueryBytes = peakHeapUsageBytes();

        line("BENCHMARK", dataset, "InMemoryCodeKnowledgeStore");
        metric("indexBytes", Files.size(indexPath));
        metric("documents", documentCount);
        metric("catalogSymbols", report.catalogSymbolCount());
        metric("normalizedSymbols", report.normalizedSymbolCount());
        metric("occurrences", report.occurrenceCount());
        metric("resolvedOccurrences", report.resolvedOccurrenceCount());
        metric("unresolvedOccurrences", report.unresolvedOccurrenceCount());
        durationMetric("indexReadMs", indexReadNanos);
        durationMetric("ingestionMs", ingestionNanos);
        durationMetric("backendReadyMs", indexReadNanos + ingestionNanos);
        metric("peakHeapIndexingBytes", peakHeapIndexingBytes);
        metric("retainedHeapAfterIngestionBytes", retainedHeapAfterIngestionBytes);
        metric("peakHeapQueryBytes", peakHeapQueryBytes);
        metric("workingStoreDiskBytes", 0);
        metric("warmupIterations", warmupIterations);
        metric("measurementIterations", measurementIterations);
        metric("queryCount", targets.size());
        metric("resultDigestStable", allDigestsStable);

        measurements.forEach(InMemoryBackendBenchmark::emitMeasurement);
        emitSummary("find_symbol", allFindSymbolSamples);
        emitSummary("find_usages", allFindUsagesSamples);
    }

    private static QueryTarget resolveTarget(
            SymbolQueryService queryService,
            String projectId,
            String query) {
        List<Symbol> results = queryService.findSymbol(projectId, query, QUERY_LIMIT);
        Symbol target = results.stream()
                .filter(symbol -> symbol.name().equals(query))
                .sorted(Comparator.comparing(Symbol::id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Query does not resolve an exact symbol name: " + query
                ));
        return new QueryTarget(query, target);
    }

    private static void warmUp(
            SymbolQueryService queryService,
            String projectId,
            List<QueryTarget> targets,
            int iterations) {
        for (int iteration = 0; iteration < iterations; iteration++) {
            for (QueryTarget target : targets) {
                serializeSymbols(queryService.findSymbol(projectId, target.query(), QUERY_LIMIT));
                serializeOccurrences(queryService.findUsages(
                        projectId,
                        target.symbol().id(),
                        USAGE_LIMIT
                ));
            }
        }
    }

    private static QueryMeasurement measure(
            String operation,
            String query,
            String targetSymbolId,
            int iterations,
            BenchmarkOperation benchmarkOperation) {
        List<Long> samples = new ArrayList<>(iterations);
        String expectedDigest = null;
        boolean stable = true;
        int resultCount = -1;

        for (int iteration = 0; iteration < iterations; iteration++) {
            long started = System.nanoTime();
            SerializedResult result = benchmarkOperation.execute();
            long elapsed = System.nanoTime() - started;
            samples.add(elapsed);

            String digest = sha256(result.content());
            if (expectedDigest == null) {
                expectedDigest = digest;
                resultCount = result.count();
            } else if (!expectedDigest.equals(digest) || resultCount != result.count()) {
                stable = false;
            }
        }

        return new QueryMeasurement(
                operation,
                query,
                targetSymbolId,
                resultCount,
                samples,
                stable,
                expectedDigest
        );
    }

    private static SerializedResult serializeSymbols(List<Symbol> symbols) {
        StringBuilder serialized = new StringBuilder();
        for (Symbol symbol : symbols) {
            serialized.append(symbol.id()).append('\u001F')
                    .append(symbol.symbolKey()).append('\u001F')
                    .append(symbol.name()).append('\u001F')
                    .append(nullToEmpty(symbol.qualifiedName())).append('\u001F')
                    .append(symbol.kind()).append('\u001F')
                    .append(symbol.identityQuality()).append('\u001F')
                    .append(symbol.providerReferences().stream()
                            .map(ProviderReference::externalId)
                            .sorted()
                            .collect(Collectors.joining("\u001E")))
                    .append('\n');
        }
        return new SerializedResult(symbols.size(), serialized.toString());
    }

    private static SerializedResult serializeOccurrences(List<SymbolOccurrence> occurrences) {
        StringBuilder serialized = new StringBuilder();
        for (SymbolOccurrence occurrence : occurrences) {
            serialized.append(occurrence.id()).append('\u001F')
                    .append(occurrence.resolvedSymbolId().orElse("")).append('\u001F')
                    .append(occurrence.location().fileId()).append('\u001F')
                    .append(occurrence.location().startLine()).append('\u001F')
                    .append(occurrence.location().startColumn()).append('\u001F')
                    .append(occurrence.roles().stream()
                            .map(Enum::name)
                            .sorted()
                            .collect(Collectors.joining(",")))
                    .append('\n');
        }
        return new SerializedResult(occurrences.size(), serialized.toString());
    }

    private static void emitMeasurement(QueryMeasurement measurement) {
        Latencies latencies = latencies(measurement.samplesNanos());
        line(
                "QUERY_METRIC",
                measurement.operation(),
                measurement.query(),
                measurement.targetSymbolId(),
                Integer.toString(measurement.resultCount()),
                formatMicros(latencies.p50Nanos()),
                formatMicros(latencies.p95Nanos()),
                formatMicros(latencies.maxNanos()),
                Boolean.toString(measurement.digestStable()),
                measurement.digest()
        );
    }

    private static void emitSummary(String operation, List<Long> samplesNanos) {
        Latencies latencies = latencies(samplesNanos);
        line(
                "SUMMARY",
                operation,
                Integer.toString(samplesNanos.size()),
                formatMicros(latencies.p50Nanos()),
                formatMicros(latencies.p95Nanos()),
                formatMicros(latencies.maxNanos())
        );
    }

    private static Latencies latencies(List<Long> samplesNanos) {
        if (samplesNanos.isEmpty()) {
            throw new IllegalArgumentException("Latency samples must not be empty");
        }
        List<Long> sorted = samplesNanos.stream().sorted().toList();
        return new Latencies(
                percentile(sorted, 50),
                percentile(sorted, 95),
                sorted.getLast()
        );
    }

    private static long percentile(List<Long> sortedSamples, int percentile) {
        int rank = (int) Math.ceil(percentile / 100.0 * sortedSamples.size());
        return sortedSamples.get(Math.max(0, rank - 1));
    }

    private static void resetPeakHeapUsage() {
        heapPools().forEach(MemoryPoolMXBean::resetPeakUsage);
    }

    private static long peakHeapUsageBytes() {
        return heapPools().stream()
                .mapToLong(pool -> Math.max(0, pool.getPeakUsage().getUsed()))
                .sum();
    }

    private static long usedHeapBytes() {
        return heapPools().stream()
                .mapToLong(pool -> Math.max(0, pool.getUsage().getUsed()))
                .sum();
    }

    private static List<MemoryPoolMXBean> heapPools() {
        return ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(pool -> pool.getType() == MemoryType.HEAP)
                .toList();
    }

    private static void forceGarbageCollection() throws InterruptedException {
        for (int attempt = 0; attempt < 3; attempt++) {
            System.gc();
            Thread.sleep(20);
        }
    }

    private static String requiredProperty(String propertyName) {
        String value = System.getProperty(propertyName);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing system property: " + propertyName);
        }
        return value;
    }

    private static int positiveIntegerProperty(String propertyName) {
        int value = Integer.parseInt(requiredProperty(propertyName));
        if (value < 1) {
            throw new IllegalArgumentException(propertyName + " must be greater than zero");
        }
        return value;
    }

    private static void durationMetric(String name, long nanos) {
        line("METRIC", name, String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0));
    }

    private static String formatMicros(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000.0);
    }

    private static void metric(String name, Object value) {
        line("METRIC", name, value.toString());
    }

    private static void line(String... values) {
        System.out.println(Arrays.stream(values)
                .map(InMemoryBackendBenchmark::sanitize)
                .collect(Collectors.joining("\t")));
    }

    private static String sanitize(String value) {
        return value == null
                ? ""
                : value.replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n");
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    @FunctionalInterface
    private interface BenchmarkOperation {
        SerializedResult execute();
    }

    private record QueryTarget(String query, Symbol symbol) {
    }

    private record SerializedResult(int count, String content) {
    }

    private record QueryMeasurement(
            String operation,
            String query,
            String targetSymbolId,
            int resultCount,
            List<Long> samplesNanos,
            boolean digestStable,
            String digest) {
    }

    private record Latencies(long p50Nanos, long p95Nanos, long maxNanos) {
    }
}
