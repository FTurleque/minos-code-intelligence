package com.minos.storage.postgresql;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresSchemaMigratorTest extends PostgresTestSupport {

    private static final List<String> BOOTSTRAP_TABLES = List.of(
            "workspaces", "projects",
            "knowledge_snapshots", "knowledge_active",
            "project_index_state", "indexing_runs",
            "fingerprint_snapshots", "fingerprint_active",
            "runtime_sessions",
            "semantic_index_meta", "semantic_documents",
            "schema_version");

    @Test
    void createsAllV1Tables() throws Exception {
        connections.withConnection(c -> {
            try (Statement s = c.createStatement()) {
                for (String table : BOOTSTRAP_TABLES) {
                    try (ResultSet r = s.executeQuery("SELECT count(*) FROM " + table)) {
                        assertTrue(r.next(), "table missing: " + table);
                    }
                }
                return null;
            }
        });
    }

    @Test
    void isIdempotentWhenCalledRepeatedly() throws Exception {
        new PostgresSchemaMigrator(connections).migrate();
        new PostgresSchemaMigrator(connections).migrate();
    }

    @Test
    void createsFingerprintRetentionTimestampAndOrderingIndex() throws Exception {
        connections.withConnection(c -> {
            try (Statement s = c.createStatement();
                 ResultSet column = s.executeQuery("""
                         SELECT count(*) FROM information_schema.columns
                         WHERE table_schema=current_schema() AND table_name='fingerprint_snapshots'
                           AND column_name='created_at'
                         """)) {
                assertTrue(column.next());
                assertTrue(column.getInt(1) == 1, "fingerprint created_at column is missing");
            }
            try (Statement s = c.createStatement();
                 ResultSet index = s.executeQuery("""
                         SELECT count(*) FROM pg_indexes
                         WHERE schemaname=current_schema() AND tablename='fingerprint_snapshots'
                           AND indexname='fingerprint_snapshots_project_created_idx'
                         """)) {
                assertTrue(index.next());
                assertTrue(index.getInt(1) == 1, "fingerprint retention index is missing");
            }
            return null;
        });
    }

    @Test
    void rejectsSchemaVersionNewerThanRuntime() throws Exception {
        connections.withConnection(c -> {
            try (Statement s = c.createStatement()) {
                s.execute("INSERT INTO schema_version(version) VALUES (999)");
                return null;
            }
        });

        IOException exception = assertThrows(IOException.class,
                () -> new PostgresSchemaMigrator(connections).migrate());
        assertTrue(exception.getMessage().contains("999"),
                "exception must mention the detected version: " + exception.getMessage());
    }

    /**
     * Two independent MINOS instances (separate connection factories, separate physical
     * connections) racing to bootstrap the SAME never-before-migrated schema must serialize
     * instead of both running CREATE EXTENSION / CREATE SCHEMA / the V1 migration concurrently.
     * A CyclicBarrier forces both migrate() calls to start within the same instant rather than
     * relying on Thread.sleep timing, so the race is exercised deterministically.
     */
    @Test
    void concurrentFreshBootstrapsOfTheSameSchemaSerializeWithoutPartialMigration() throws Exception {
        String schema = "minos_concurrent_boot";
        PostgresConnectionFactory first = createFactory(schema);
        PostgresConnectionFactory second = createFactory(schema);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            List<Callable<Exception>> tasks = List.of(
                    migrationTask(barrier, first),
                    migrationTask(barrier, second));
            List<Future<Exception>> futures = executor.invokeAll(tasks, 60, TimeUnit.SECONDS);
            for (Future<Exception> future : futures) {
                Exception failure = future.get();
                if (failure != null) {
                    throw new AssertionError("concurrent migrate() must not fail", failure);
                }
            }
        } finally {
            executor.shutdownNow();
            first.close();
            second.close();
        }

        assertFullyBootstrapped(connections, schema);
    }

    /**
     * Two independent MINOS instances bootstrapping two DIFFERENT schemas concurrently must also
     * serialize, because the bootstrap mutates database-wide state: {@code CREATE EXTENSION IF NOT
     * EXISTS vector} writes {@code pg_extension}, which is per-database. With a per-schema lock key
     * both callers would observe the extension missing and both would issue CREATE EXTENSION, so one
     * would fail on the {@code pg_extension} unique index.
     *
     * <p>The race is run against a freshly created database precisely so that the extension really
     * does not exist yet -- against the already-migrated default test database {@code IF NOT EXISTS}
     * would short-circuit and the step under test would never actually race.</p>
     */
    @Test
    void concurrentFreshBootstrapsOfDistinctSchemasSerializeDatabaseWideState() throws Exception {
        String database = "minos_bootstrap_race";
        String schemaA = "minos_concurrent_a";
        String schemaB = "minos_concurrent_b";
        createFreshDatabase(database);
        PostgresConnectionFactory first = createFactoryForDatabase(database, schemaA);
        PostgresConnectionFactory second = createFactoryForDatabase(database, schemaB);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            List<Callable<Exception>> tasks = List.of(
                    migrationTask(barrier, first),
                    migrationTask(barrier, second));
            List<Future<Exception>> futures = executor.invokeAll(tasks, 120, TimeUnit.SECONDS);
            for (Future<Exception> future : futures) {
                Exception failure = future.get();
                if (failure != null) {
                    throw new AssertionError("concurrent cross-schema migrate() must not fail", failure);
                }
            }

            first.withConnection(c -> {
                try (Statement s = c.createStatement();
                     ResultSet r = s.executeQuery("SELECT '[1,2,3]'::vector")) {
                    assertTrue(r.next(), "pgvector extension must be usable after a concurrent bootstrap");
                }
                return null;
            });
            assertFullyBootstrapped(first, schemaA);
            assertFullyBootstrapped(second, schemaB);
        } finally {
            executor.shutdownNow();
            first.close();
            second.close();
        }
    }

    private static void assertFullyBootstrapped(PostgresConnectionFactory factory, String schema) throws Exception {
        factory.withConnection(c -> {
            try (Statement s = c.createStatement();
                 ResultSet r = s.executeQuery(
                         "SELECT version FROM " + schema + ".schema_version ORDER BY version")) {
                List<Integer> versions = new ArrayList<>();
                while (r.next()) versions.add(r.getInt(1));
                assertEquals(List.of(1, 2, 3, 4), versions,
                        "schema_version of " + schema + " must contain exactly versions 1..4 once each,"
                                + " with no partial or duplicate migration");
            }
            try (Statement s = c.createStatement()) {
                for (String table : BOOTSTRAP_TABLES) {
                    try (ResultSet r = s.executeQuery("SELECT count(*) FROM " + schema + "." + table)) {
                        assertTrue(r.next(), "table missing after concurrent bootstrap in " + schema + ": " + table);
                    }
                }
            }
            return null;
        });
    }

    private static Callable<Exception> migrationTask(CyclicBarrier barrier, PostgresConnectionFactory factory) {
        return () -> {
            try {
                barrier.await(30, TimeUnit.SECONDS);
                new PostgresSchemaMigrator(factory).migrate();
                return null;
            } catch (Exception failure) {
                return failure;
            }
        };
    }
}
