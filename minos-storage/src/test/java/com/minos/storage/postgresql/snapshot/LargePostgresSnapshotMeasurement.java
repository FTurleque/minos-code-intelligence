package com.minos.storage.postgresql.snapshot;

import com.minos.storage.codec.QueryViewWeightFixtures;
import com.minos.storage.codec.SnapshotCodec;
import com.minos.storage.codec.SnapshotCodecV2;
import com.minos.storage.codec.SnapshotCodecV3;
import com.minos.storage.postgresql.PostgresTestSupport;
import com.minos.store.CodeKnowledgeSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Publishes a large snapshot into the managed PostgreSQL store and reads it back, to measure what a payload of that
 * size costs there: publication time, the heap the JDBC driver holds while the payload is fetched (the transient that
 * {@code SnapshotLimits.fetchTransientBytes} reserves), cold read, consecutive reads and the query cache outcome. Not
 * run by {@code verify}: the name matches no Surefire pattern, so it runs only when named, against the PostgreSQL test
 * server ({@code scripts/dev/start-test-postgres.sh} or {@code .ps1}).
 *
 * <pre>
 *   ./mvnw -pl minos-storage -am test -Dtest=LargePostgresSnapshotMeasurement \
 *       -Dminos.largeSnapshot.store=&lt;MINOS_HOME&gt;/symbol-snapshots -Dminos.largeSnapshot.project=&lt;projectId&gt; \
 *       -DargLine=-Xmx4g
 *   ./mvnw -pl minos-storage -am test -Dtest=LargePostgresSnapshotMeasurement \
 *       -Dminos.largeSnapshot.files=&lt;source files of a synthetic repository&gt; -DargLine=-Xmx4g
 * </pre>
 *
 * <p>The file store is read through the shared codec package, not through the store, so that this class names no
 * other backend. A snapshot above the persisted ceiling cannot be read by an unmodified build; measuring one is the
 * purpose of a build whose ceiling was raised locally.</p>
 */
class LargePostgresSnapshotMeasurement extends PostgresTestSupport {

    private static final String STORE_PROPERTY = "minos.largeSnapshot.store";
    private static final String PROJECT_PROPERTY = "minos.largeSnapshot.project";
    private static final String FILES_PROPERTY = "minos.largeSnapshot.files";
    private static final int FETCHES = 8;
    private static final int READS = 4;

    @TempDir
    Path spool;

    @Test
    void measuresAPayloadOfTheSizeOfALargeProject() throws Exception {
        String files = System.getProperty(FILES_PROPERTY);
        String store = System.getProperty(STORE_PROPERTY);
        String project = System.getProperty(PROJECT_PROPERTY);
        // A measurement, not a check: a run that names no snapshot (verify, PIT) skips it instead of failing.
        assumeTrue(files != null || (store != null && project != null),
                "set -D" + FILES_PROPERTY + ", or -D" + STORE_PROPERTY + " and -D" + PROJECT_PROPERTY);
        UUID projectId = files != null ? UUID.randomUUID() : UUID.fromString(project);
        CodeKnowledgeSnapshot snapshot = files != null
                ? QueryViewWeightFixtures.repository(projectId, "measure", Integer.parseInt(files),
                        List.of(QueryViewWeightFixtures.ASCII, QueryViewWeightFixtures.LATIN1, QueryViewWeightFixtures.CJK))
                : readLargestSnapshot(Path.of(store).resolve(project));
        print("symbols", snapshot.symbols().size());
        print("occurrences", snapshot.occurrences().size());
        print("relationships", snapshot.relationships().size());

        PostgresCodeKnowledgeSnapshotStore postgres = new PostgresCodeKnowledgeSnapshotStore(connections, spool);
        long started = System.nanoTime();
        postgres.publish(projectId, "measure", snapshot.symbols(), snapshot.occurrences(), snapshot.relationships());
        print("publish.millis", millis(started));
        snapshot = null;

        print("jvm.maxHeap.bytes", Runtime.getRuntime().maxMemory());
        measureFetchTransient(projectId);

        for (int read = 1; read <= READS; read++) {
            long readStarted = System.nanoTime();
            postgres.loadActiveReadView(projectId).orElseThrow();
            print("read." + read + ".millis", millis(readStarted));
        }
        PostgresCodeKnowledgeSnapshotStore.QueryCacheStats stats = postgres.queryCacheStats();
        print("cache.hits", stats.hits());
        print("cache.misses", stats.misses());
        print("cache.entries", stats.entries());
    }

    /**
     * Fetches the active payload the way the store does (one row, {@code getBinaryStream} on the {@code bytea}
     * column, streamed through a small buffer) and records the heap peak above the settled heap for each fetch. The
     * fetch is repeated on the pool so that the driver's switch to server-prepared statements is measured too.
     */
    private void measureFetchTransient(UUID projectId) throws Exception {
        long payloadBytes = connections.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT octet_length(s.payload) FROM knowledge_active a JOIN knowledge_snapshots s "
                            + "ON s.project_id=a.project_id AND s.snapshot_id=a.snapshot_id WHERE a.project_id=?")) {
                statement.setObject(1, projectId);
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    return result.getLong(1);
                }
            }
        });
        print("payload.bytes", payloadBytes);
        long maximum = 0L;
        for (int fetch = 1; fetch <= FETCHES; fetch++) {
            long before = settledHeap();
            resetPeaks();
            long streamed = connections.withConnection(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT s.snapshot_id,s.payload,s.sha256 FROM knowledge_active a JOIN knowledge_snapshots s "
                                + "ON s.project_id=a.project_id AND s.snapshot_id=a.snapshot_id WHERE a.project_id=?")) {
                    statement.setObject(1, projectId);
                    try (ResultSet result = statement.executeQuery()) {
                        result.next();
                        return drain(result.getBinaryStream(2));
                    }
                }
            });
            long transientBytes = peakHeap() - before;
            maximum = Math.max(maximum, transientBytes);
            print("fetch." + fetch + ".streamed.bytes", streamed);
            print("fetch." + fetch + ".transient.bytes", transientBytes);
            print("fetch." + fetch + ".transient.over.payload", ratio(transientBytes, payloadBytes));
        }
        print("fetch.transient.max.over.payload", ratio(maximum, payloadBytes));
    }

    private static long drain(InputStream input) throws IOException {
        try (input) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0L;
            for (int read = input.read(buffer); read >= 0; read = input.read(buffer)) {
                total += read;
            }
            return total;
        }
    }

    /** Reads the largest {@code .knowledge} file of a file-store project directory through the shared codec package. */
    private static CodeKnowledgeSnapshot readLargestSnapshot(Path projectDirectory) throws IOException {
        Path file;
        try (Stream<Path> files = Files.list(projectDirectory)) {
            file = files.filter(path -> path.toString().endsWith(".knowledge"))
                    .max(java.util.Comparator.comparingLong(LargePostgresSnapshotMeasurement::size)).orElseThrow();
        }
        for (SnapshotCodec codec : new SnapshotCodec[] {new SnapshotCodecV3(), new SnapshotCodecV2()}) {
            try {
                return codec.read(file);
            } catch (IOException wrongFormat) {
                // the other codec reads this file
            }
        }
        throw new IOException("no codec reads the snapshot");
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

    private static String ratio(long numerator, long denominator) {
        return String.format(Locale.ROOT, "%.2f", (double) numerator / denominator);
    }

    private static long millis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    private static void print(String key, Object value) {
        System.out.println(String.format(Locale.ROOT, "LARGE-POSTGRES-SNAPSHOT %s=%s", key, value));
    }
}
