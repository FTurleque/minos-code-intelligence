package com.minos.storage.postgresql;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresSchemaMigratorTest extends PostgresTestSupport {

    @Test
    void createsAllV1Tables() throws Exception {
        String[] tables = {
                "workspaces", "projects",
                "knowledge_snapshots", "knowledge_active",
                "project_index_state", "indexing_runs",
                "fingerprint_snapshots", "fingerprint_active",
                "runtime_sessions",
                "semantic_index_meta", "semantic_documents",
                "schema_version"
        };
        connections.withConnection(c -> {
            try (Statement s = c.createStatement()) {
                for (String table : tables) {
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
                         WHERE table_schema='public' AND table_name='fingerprint_snapshots'
                           AND column_name='created_at'
                         """)) {
                assertTrue(column.next());
                assertTrue(column.getInt(1) == 1, "fingerprint created_at column is missing");
            }
            try (Statement s = c.createStatement();
                 ResultSet index = s.executeQuery("""
                         SELECT count(*) FROM pg_indexes
                         WHERE schemaname='public' AND tablename='fingerprint_snapshots'
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
}
