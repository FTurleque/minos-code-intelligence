package com.minos.storage.postgresql.snapshot;

import com.minos.storage.codec.SnapshotCodec;
import com.minos.storage.codec.SnapshotCodecV2;
import com.minos.storage.codec.SnapshotCodecV3;
import com.minos.storage.postgresql.PostgresTestSupport;
import com.minos.store.CodeKnowledgeSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Publishes a large file-store snapshot into the managed PostgreSQL store and reads it back, to measure what a
 * payload of that size costs there: publication time, cold read, consecutive reads and the query cache outcome.
 * Not run by {@code verify}: the name matches no Surefire pattern, so it runs only when named, with Docker available
 * and the pinned pgvector image already pulled.
 *
 * <pre>
 *   ./mvnw -pl minos-storage -am test -Dtest=LargePostgresSnapshotMeasurement \
 *       -Dminos.largeSnapshot.store=&lt;MINOS_HOME&gt;/symbol-snapshots -Dminos.largeSnapshot.project=&lt;projectId&gt; \
 *       -DargLine=-Xmx4g
 * </pre>
 *
 * <p>The file store is read through the shared codec package, not through the store, so that this class names no
 * other backend. A snapshot above the persisted ceiling cannot be read by an unmodified build; measuring one is the purpose of a
 * build whose ceiling was raised locally.</p>
 */
class LargePostgresSnapshotMeasurement extends PostgresTestSupport {

    private static final String STORE_PROPERTY = "minos.largeSnapshot.store";
    private static final String PROJECT_PROPERTY = "minos.largeSnapshot.project";

    @TempDir
    Path spool;

    @Test
    void measuresAPayloadOfTheSizeOfALargeProject() throws Exception {
        String store = System.getProperty(STORE_PROPERTY);
        String project = System.getProperty(PROJECT_PROPERTY);
        assertTrue(store != null && project != null, "set -D" + STORE_PROPERTY + " and -D" + PROJECT_PROPERTY);
        UUID projectId = UUID.fromString(project);
        CodeKnowledgeSnapshot snapshot = readLargestSnapshot(Path.of(store).resolve(project));
        print("symbols", snapshot.symbols().size());
        print("occurrences", snapshot.occurrences().size());
        print("relationships", snapshot.relationships().size());

        PostgresCodeKnowledgeSnapshotStore postgres = new PostgresCodeKnowledgeSnapshotStore(connections, spool);
        long started = System.nanoTime();
        postgres.publish(projectId, "measure", snapshot.symbols(), snapshot.occurrences(), snapshot.relationships());
        print("publish.millis", millis(started));
        snapshot = null;

        for (int read = 1; read <= 4; read++) {
            long readStarted = System.nanoTime();
            postgres.loadActiveReadView(projectId).orElseThrow();
            print("read." + read + ".millis", millis(readStarted));
        }
        PostgresCodeKnowledgeSnapshotStore.QueryCacheStats stats = postgres.queryCacheStats();
        print("cache.hits", stats.hits());
        print("cache.misses", stats.misses());
        print("cache.entries", stats.entries());
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

    private static long millis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    private static void print(String key, Object value) {
        System.out.println(String.format(Locale.ROOT, "LARGE-POSTGRES-SNAPSHOT %s=%s", key, value));
    }
}
