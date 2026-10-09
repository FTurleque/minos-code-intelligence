package com.minos.storage.postgresql;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MINOS-AUD-H12 : une base créée au schéma v1 et peuplée migre jusqu'à la version courante sans perte, et une étape
 * qui échoue annule toute la migration au lieu de laisser un schéma à mi-chemin.
 */
class PostgresSchemaUpgradeFromV1Test extends PostgresTestSupport {
    private static final String WORKSPACE = "11111111-0000-0000-0000-000000000001";
    private static final String PROJECT = "22222222-0000-0000-0000-000000000002";

    @Test
    void aPopulatedV1DatabaseMigratesToTheCurrentVersionWithItsData() throws Exception {
        PostgresConnectionFactory factory = v1Database("minos_upgrade_v1");
        try {
            populate(factory, false);

            new PostgresSchemaMigrator(factory).migrate();

            assertEquals(List.of(1, 2, 3, 4), versions(factory));
            assertEquals(PostgresSchemaMigrator.CURRENT_VERSION, versions(factory).getLast());
            assertEquals("demo", single(factory, "SELECT display_name FROM projects WHERE id='" + PROJECT + "'"));
            assertEquals("snap-1", single(factory, "SELECT snapshot_id FROM knowledge_active"));
            assertEquals("3", single(factory, "SELECT symbol_count FROM knowledge_snapshots"));
            assertEquals("t", single(factory, "SELECT created_at IS NOT NULL FROM fingerprint_snapshots"));
            assertEquals("2", single(factory, "SELECT count(*) FROM pg_indexes WHERE schemaname=current_schema()"
                    + " AND indexname IN ('projects_root_identity_uq','workspaces_name_uq')"));
        } finally {
            factory.close();
        }
    }

    @Test
    void aFailingStepRollsBackTheWholeMigration() throws Exception {
        PostgresConnectionFactory factory = v1Database("minos_upgrade_v1_failing");
        try {
            populate(factory, true);

            IOException failure = assertThrows(IOException.class, () -> new PostgresSchemaMigrator(factory).migrate());

            assertTrue(String.valueOf(failure.getCause()).contains("duplicate workspace names"), failure.toString());
            assertEquals(List.of(1), versions(factory), "no step is recorded when a later one fails");
            assertEquals("0", single(factory, "SELECT count(*) FROM pg_indexes WHERE schemaname=current_schema()"
                    + " AND indexname='projects_root_identity_uq'"), "the v2 index is rolled back with v4");
            assertEquals("0", single(factory, "SELECT count(*) FROM information_schema.columns"
                    + " WHERE table_schema=current_schema() AND table_name='fingerprint_snapshots'"
                    + " AND column_name='created_at'"), "the v3 column is rolled back with v4");
            assertEquals("demo", single(factory, "SELECT display_name FROM projects"));
        } finally {
            factory.close();
        }
    }

    private PostgresConnectionFactory v1Database(String schema) throws Exception {
        String sql;
        try (InputStream in = getClass().getResourceAsStream("/schema/v1.sql")) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        connections.withConnection(c -> {
            try (Statement s = c.createStatement()) {
                s.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
                s.execute("CREATE SCHEMA " + schema);
            }
            return null;
        });
        PostgresConnectionFactory factory = createFactory(schema);
        factory.withConnection(c -> {
            try (Statement s = c.createStatement()) {
                for (String statement : sql.replaceAll("(?m)^--.*$", "").split(";")) {
                    if (!statement.isBlank()) s.execute(statement.strip());
                }
            }
            return null;
        });
        return factory;
    }

    private static void populate(PostgresConnectionFactory factory, boolean duplicateWorkspaceName) throws Exception {
        factory.withConnection(c -> {
            try (Statement s = c.createStatement()) {
                s.execute("INSERT INTO workspaces VALUES ('" + WORKSPACE + "', 'team', now(), now())");
                if (duplicateWorkspaceName) {
                    s.execute("INSERT INTO workspaces VALUES ('33333333-0000-0000-0000-000000000003', 'team', now(), now())");
                }
                s.execute("INSERT INTO projects VALUES ('" + PROJECT + "', '/src/demo', true, 'demo', '" + WORKSPACE
                        + "', now(), now())");
                s.execute("INSERT INTO knowledge_snapshots(project_id, snapshot_id, payload, sha256, symbol_count,"
                        + " occurrence_count, relationship_count) VALUES ('" + PROJECT + "', 'snap-1', '\\x00'::bytea,"
                        + " repeat('a', 64), 3, 0, 0)");
                s.execute("INSERT INTO knowledge_active VALUES ('" + PROJECT + "', 'snap-1')");
                s.execute("INSERT INTO fingerprint_snapshots VALUES ('" + PROJECT + "', 'fp-1', '{}'::jsonb)");
                s.execute("INSERT INTO fingerprint_active VALUES ('" + PROJECT + "', 'fp-1')");
            }
            return null;
        });
    }

    private static List<Integer> versions(PostgresConnectionFactory factory) throws Exception {
        return factory.withConnection(c -> {
            List<Integer> versions = new ArrayList<>();
            try (Statement s = c.createStatement();
                 ResultSet r = s.executeQuery("SELECT version FROM schema_version ORDER BY version")) {
                while (r.next()) versions.add(r.getInt(1));
            }
            return versions;
        });
    }

    private static String single(PostgresConnectionFactory factory, String query) throws Exception {
        return factory.withConnection(c -> {
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(query)) {
                assertTrue(r.next(), query);
                return r.getString(1);
            }
        });
    }
}
