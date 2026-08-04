package com.minos.storage.postgresql;

import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

final class PostgresSchemaMigrator {
    static final int CURRENT_VERSION = 1;

    private final PostgresConnectionFactory connections;

    PostgresSchemaMigrator(PostgresConnectionFactory connections) {
        this.connections = connections;
    }

    void migrate() throws IOException {
        String schema = connections.schema();
        try (Connection connection = connections.open(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                statement.execute("CREATE EXTENSION IF NOT EXISTS vector");
                statement.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
                statement.execute("SET search_path TO " + schema + ", public");
                statement.execute("CREATE TABLE IF NOT EXISTS schema_version (version integer PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now())");
                int version = currentVersion(statement);
                if (version > CURRENT_VERSION) throw new IOException("PostgreSQL schema is newer than this MINOS runtime: " + version);
                if (version < 1) applyV1(statement);
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                if (exception instanceof IOException io) throw io;
                throw new IOException("unable to migrate MINOS PostgreSQL schema", exception);
            }
        } catch (SQLException exception) {
            throw new IOException("unable to initialize MINOS PostgreSQL backend", exception);
        }
    }

    private static int currentVersion(Statement statement) throws SQLException {
        try (ResultSet result = statement.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_version")) {
            result.next();
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
}
