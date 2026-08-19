package com.minos.storage.postgresql;

import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

final class PostgresSchemaMigrator {
    static final int CURRENT_VERSION = 4;

    private final PostgresConnectionFactory connections;

    PostgresSchemaMigrator(PostgresConnectionFactory connections) {
        this.connections = connections;
    }

    void migrate() throws IOException {
        try {
            connections.inTransaction(connection -> {
                try (Statement statement = connection.createStatement()) {
                    String schema = connections.schema();
                    String quotedSchema = statement.enquoteIdentifier(schema, true);
                    if (quotedSchema.isBlank()) {
                        throw new SQLException("PostgreSQL schema quoting produced an empty identifier");
                    }
                    // Serialize the entire bootstrap behind ONE database-global advisory lock, acquired as
                    // the FIRST statement of this transaction. pg_advisory_xact_lock is fair-FIFO and is
                    // released automatically at commit/rollback, so a concurrent caller simply waits and
                    // then observes the fully-migrated database instead of colliding with it.
                    //
                    // The key deliberately carries NO schema component. The bootstrap mutates database-wide
                    // catalog state -- CREATE EXTENSION IF NOT EXISTS vector writes pg_extension, which is
                    // per-database, not per-schema. A per-schema key would let bootstraps of two *different*
                    // schemas run concurrently and both find the extension missing, so both would issue
                    // CREATE EXTENSION and one would fail on the pg_extension unique index.
                    //
                    // Holding this one lock for the whole transaction also covers the per-schema migrations
                    // below. Bootstraps of distinct schemas therefore serialize rather than run in parallel.
                    // That is intentional: bootstrap runs once per backend construction and is short, so a
                    // single easily-audited lock is worth more than parallelism here.
                    statement.execute(
                            "SELECT pg_advisory_xact_lock(hashtext('minos-storage-postgresql'), hashtext('database-bootstrap'))");
                    try (PreparedStatement schemaSetting = connection.prepareStatement(
                            "SELECT set_config('minos.migration_schema', ?, true)")) {
                        schemaSetting.setString(1, schema);
                        schemaSetting.execute();
                    }
                    statement.execute("CREATE EXTENSION IF NOT EXISTS vector");
                    statement.execute("DO $minos$ DECLARE target_schema text := "
                            + "current_setting('minos.migration_schema', true); "
                            + "BEGIN EXECUTE format('CREATE SCHEMA IF NOT EXISTS %I', target_schema); END $minos$");
                    statement.execute("CREATE TABLE IF NOT EXISTS schema_version (version integer PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now())");
                    int version = currentVersion(statement);
                    if (version > CURRENT_VERSION) {
                        throw new IOException("PostgreSQL schema is newer than this MINOS runtime: " + version);
                    }
                    if (version < 1) applyV1(statement);
                    if (version < 2) applyV2(statement);
                    if (version < 3) applyV3(statement);
                    if (version < 4) applyV4(statement);
                    return null;
                }
            });
        } catch (SQLException exception) {
            throw new IOException("unable to initialize MINOS PostgreSQL backend", exception);
        }
    }

    private static int currentVersion(Statement statement) throws SQLException {
        try (ResultSet result = statement.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_version")) {
            if (!result.next()) throw new SQLException("schema_version query returned no row");
            return result.getInt(1);
        }
    }

    private static void applyV1(Statement s) throws SQLException {
        s.execute("CREATE TABLE workspaces (id uuid PRIMARY KEY, name text NOT NULL, created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL)");
        s.execute("CREATE TABLE projects (id uuid PRIMARY KEY, root_value text NOT NULL, root_portable boolean NOT NULL, display_name text NOT NULL, workspace_id uuid NULL REFERENCES workspaces(id), created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL)");
        s.execute("CREATE INDEX projects_display_name_idx ON projects(display_name)");

        s.execute("CREATE TABLE knowledge_snapshots (project_id uuid NOT NULL, snapshot_id text NOT NULL, payload bytea NOT NULL, sha256 char(64) NOT NULL, symbol_count integer NOT NULL, occurrence_count integer NOT NULL, relationship_count integer NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(project_id, snapshot_id))");
        s.execute("CREATE TABLE knowledge_active (project_id uuid PRIMARY KEY, snapshot_id text NOT NULL)");

        s.execute("CREATE TABLE project_index_state (project_id uuid PRIMARY KEY, availability text NOT NULL, active_snapshot_id text NULL, latest_run_id uuid NULL, updated_at timestamptz NOT NULL, detail text NULL)");
        s.execute("CREATE TABLE indexing_runs (id uuid PRIMARY KEY, project_id uuid NOT NULL, created_at timestamptz NOT NULL, payload jsonb NOT NULL)");
        s.execute("CREATE INDEX indexing_runs_project_idx ON indexing_runs(project_id, created_at)");

        s.execute("CREATE TABLE fingerprint_snapshots (project_id uuid NOT NULL, snapshot_id text NOT NULL, payload jsonb NOT NULL, PRIMARY KEY(project_id, snapshot_id))");
        s.execute("CREATE TABLE fingerprint_active (project_id uuid PRIMARY KEY, snapshot_id text NOT NULL)");

        s.execute("CREATE TABLE runtime_sessions (project_id uuid NOT NULL, session_id text NOT NULL, source_sha256 char(64) NOT NULL, imported_at timestamptz NOT NULL, payload jsonb NOT NULL, PRIMARY KEY(project_id, session_id))");
        s.execute("CREATE INDEX runtime_sessions_project_idx ON runtime_sessions(project_id, imported_at DESC)");

        s.execute("CREATE TABLE semantic_index_meta (project_id uuid PRIMARY KEY, snapshot_id text NOT NULL, provider_id text NOT NULL, model_id text NOT NULL, dimensions integer NOT NULL CHECK(dimensions > 0 AND dimensions <= 16384), built_at bigint NOT NULL)");
        s.execute("CREATE TABLE semantic_documents (project_id uuid NOT NULL, stable_key text NOT NULL, document_id text NOT NULL, snapshot_id text NOT NULL, kind text NOT NULL, source_id text NOT NULL, file_id text NULL, start_line integer NOT NULL, end_line integer NOT NULL, content text NOT NULL, checksum text NOT NULL, embedding vector NOT NULL, PRIMARY KEY(project_id, stable_key))");
        s.execute("CREATE INDEX semantic_documents_project_idx ON semantic_documents(project_id)");

        s.execute("INSERT INTO schema_version(version) VALUES (1)");
    }

    private static void applyV2(Statement s) throws SQLException {
        try (ResultSet duplicates = s.executeQuery(
                "SELECT root_value, root_portable, COUNT(*) FROM projects "
                        + "GROUP BY root_value, root_portable HAVING COUNT(*) > 1 LIMIT 1")) {
            if (duplicates.next()) {
                throw new SQLException("duplicate project roots prevent schema v2 uniqueness migration");
            }
        }
        s.execute("CREATE UNIQUE INDEX IF NOT EXISTS projects_root_identity_uq ON projects(root_value, root_portable)");
        s.execute("INSERT INTO schema_version(version) VALUES (2) ON CONFLICT(version) DO NOTHING");
    }

    private static void applyV3(Statement s) throws SQLException {
        s.execute("ALTER TABLE fingerprint_snapshots ADD COLUMN IF NOT EXISTS created_at timestamptz NOT NULL DEFAULT now()");
        s.execute("CREATE INDEX IF NOT EXISTS fingerprint_snapshots_project_created_idx "
                + "ON fingerprint_snapshots(project_id, created_at DESC, snapshot_id DESC)");
        s.execute("INSERT INTO schema_version(version) VALUES (3) ON CONFLICT(version) DO NOTHING");
    }

    private static void applyV4(Statement s) throws SQLException {
        try (ResultSet duplicates = s.executeQuery(
                "SELECT name, COUNT(*) FROM workspaces GROUP BY name HAVING COUNT(*) > 1 LIMIT 1")) {
            if (duplicates.next()) {
                throw new SQLException("duplicate workspace names prevent schema v4 uniqueness migration");
            }
        }
        s.execute("CREATE UNIQUE INDEX IF NOT EXISTS workspaces_name_uq ON workspaces(name)");
        s.execute("INSERT INTO schema_version(version) VALUES (4) ON CONFLICT(version) DO NOTHING");
    }
}
