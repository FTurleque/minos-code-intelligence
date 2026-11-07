package com.minos.storage.local.store;

import com.minos.storage.codec.QueryViewWeightFixtures;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.IncomingRelationships;
import com.minos.store.SnapshotReadView;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Measures what a large active snapshot costs to read and to cache: persisted size, estimated weight, heap retained
 * by the view with its incoming-relationship index built, peak heap while decoding, and the load behaviour of
 * consecutive reads. A {@code main} of the test sources, never run by {@code verify} (its name matches no Surefire
 * pattern, like the scalability benchmark); no measure is written into the repository.
 *
 * <pre>
 *   synthetic &lt;storageRoot&gt; &lt;files&gt;           publish a synthetic repository of &lt;files&gt; source files, then measure
 *   existing  &lt;storageRoot&gt; &lt;projectId&gt;      measure the active snapshot of a store (a MINOS_HOME/symbol-snapshots)
 * </pre>
 *
 * <p>A snapshot above the persisted ceiling cannot be published or read by an unmodified build; measuring one is
 * the purpose of a build whose ceiling was raised locally.</p>
 */
public final class LargeSnapshotMeasurement {

    private static final int READS = 4;

    private LargeSnapshotMeasurement() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("usage: synthetic <storageRoot> <files> | existing <storageRoot> <projectId>");
        }
        Path root = Path.of(args[1]);
        UUID projectId;
        switch (args[0]) {
            case "synthetic" -> {
                projectId = UUID.randomUUID();
                publishSynthetic(root, projectId, Integer.parseInt(args[2]));
            }
            case "existing" -> projectId = UUID.fromString(args[2]);
            default -> throw new IllegalArgumentException("unknown mode: " + args[0]);
        }
        measure(root, projectId);
    }

    private static void publishSynthetic(Path root, UUID projectId, int files) throws IOException {
        CodeKnowledgeSnapshot snapshot = QueryViewWeightFixtures.repository(projectId, "measure", files,
                List.of(QueryViewWeightFixtures.ASCII, QueryViewWeightFixtures.LATIN1, QueryViewWeightFixtures.CJK));
        long started = System.nanoTime();
        new FileSymbolSnapshotStore(root).publish(projectId, "measure", snapshot.symbols(), snapshot.occurrences(),
                snapshot.relationships());
        print("publish.millis", millis(started));
        print("symbols", snapshot.symbols().size());
        print("occurrences", snapshot.occurrences().size());
        print("relationships", snapshot.relationships().size());
    }

    private static void measure(Path root, UUID projectId) throws Exception {
        FileSymbolSnapshotStore reader = new FileSymbolSnapshotStore(root);
        print("persisted.bytes", persistedBytes(root.resolve(projectId.toString())));
        print("jvm.maxHeap.bytes", Runtime.getRuntime().maxMemory());
        long before = settledHeap();
        resetPeaks();
        long started = System.nanoTime();
        SnapshotReadView view = reader.loadActiveReadView(projectId).orElseThrow();
        IncomingRelationships edges = view.incomingRelationships();
        long coldMillis = millis(started);
        long peak = peakHeap();
        long retained = settledHeap() - before;
        FileSymbolSnapshotStore.CacheStats stats = reader.cacheStats();
        print("edges.targets", edges.targetCount());
        print("cold.load.millis", coldMillis);
        print("peak.heap.bytes", peak);
        print("retained.bytes", retained);
        print("estimate.bytes", stats.weightBytes());
        print("cache.maximum.bytes", stats.maximumWeightBytes());
        print("estimate.over.retained", ratio(stats.weightBytes(), retained));
        print("retained.over.persisted", ratio(retained, persistedBytes(root.resolve(projectId.toString()))));
        for (int read = 1; read <= READS; read++) {
            long readStarted = System.nanoTime();
            reader.loadActiveReadView(projectId).orElseThrow();
            print("read." + read + ".millis", millis(readStarted));
        }
        FileSymbolSnapshotStore.CacheStats after = reader.cacheStats();
        print("cache.entries", after.entries());
        print("cache.hits", after.hits());
        print("cache.misses", after.misses());
        print("cache.fullSnapshotLoads", after.fullSnapshotLoads());
    }

    private static long persistedBytes(Path projectDirectory) throws IOException {
        try (Stream<Path> files = Files.walk(projectDirectory)) {
            return files.filter(Files::isRegularFile).mapToLong(LargeSnapshotMeasurement::size).max().orElseThrow();
        }
    }

    private static long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    private static void resetPeaks() {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP) pool.resetPeakUsage();
        }
    }

    private static long peakHeap() {
        long total = 0L;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP) total += pool.getPeakUsage().getUsed();
        }
        return total;
    }

    private static long settledHeap() throws InterruptedException {
        for (int round = 0; round < 4; round++) {
            System.gc();
            Thread.sleep(150L);
        }
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private static long millis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    private static String ratio(long numerator, long denominator) {
        return denominator == 0L ? "n/a" : String.format(Locale.ROOT, "%.2f", numerator / (double) denominator);
    }

    private static void print(String key, Object value) {
        System.out.println("LARGE-SNAPSHOT " + key + "=" + value);
    }
}
