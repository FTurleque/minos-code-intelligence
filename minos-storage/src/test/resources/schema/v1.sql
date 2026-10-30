-- MINOS PostgreSQL schema v1, frozen copy of PostgresSchemaMigrator.applyV1 (MINOS-AUD-H12).
-- Never edit: it reproduces databases created by the first releases, to prove they still migrate.
CREATE TABLE schema_version (version integer PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE workspaces (id uuid PRIMARY KEY, name text NOT NULL, created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL);
CREATE TABLE projects (id uuid PRIMARY KEY, root_value text NOT NULL, root_portable boolean NOT NULL, display_name text NOT NULL, workspace_id uuid NULL REFERENCES workspaces(id), created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL);
CREATE INDEX projects_display_name_idx ON projects(display_name);
CREATE TABLE knowledge_snapshots (project_id uuid NOT NULL, snapshot_id text NOT NULL, payload bytea NOT NULL, sha256 char(64) NOT NULL, symbol_count integer NOT NULL, occurrence_count integer NOT NULL, relationship_count integer NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(project_id, snapshot_id));
CREATE TABLE knowledge_active (project_id uuid PRIMARY KEY, snapshot_id text NOT NULL);
CREATE TABLE project_index_state (project_id uuid PRIMARY KEY, availability text NOT NULL, active_snapshot_id text NULL, latest_run_id uuid NULL, updated_at timestamptz NOT NULL, detail text NULL);
CREATE TABLE indexing_runs (id uuid PRIMARY KEY, project_id uuid NOT NULL, created_at timestamptz NOT NULL, payload jsonb NOT NULL);
CREATE INDEX indexing_runs_project_idx ON indexing_runs(project_id, created_at);
CREATE TABLE fingerprint_snapshots (project_id uuid NOT NULL, snapshot_id text NOT NULL, payload jsonb NOT NULL, PRIMARY KEY(project_id, snapshot_id));
CREATE TABLE fingerprint_active (project_id uuid PRIMARY KEY, snapshot_id text NOT NULL);
CREATE TABLE runtime_sessions (project_id uuid NOT NULL, session_id text NOT NULL, source_sha256 char(64) NOT NULL, imported_at timestamptz NOT NULL, payload jsonb NOT NULL, PRIMARY KEY(project_id, session_id));
CREATE INDEX runtime_sessions_project_idx ON runtime_sessions(project_id, imported_at DESC);
CREATE TABLE semantic_index_meta (project_id uuid PRIMARY KEY, snapshot_id text NOT NULL, provider_id text NOT NULL, model_id text NOT NULL, dimensions integer NOT NULL CHECK(dimensions > 0 AND dimensions <= 16384), built_at bigint NOT NULL);
CREATE TABLE semantic_documents (project_id uuid NOT NULL, stable_key text NOT NULL, document_id text NOT NULL, snapshot_id text NOT NULL, kind text NOT NULL, source_id text NOT NULL, file_id text NULL, start_line integer NOT NULL, end_line integer NOT NULL, content text NOT NULL, checksum text NOT NULL, embedding vector NOT NULL, PRIMARY KEY(project_id, stable_key));
CREATE INDEX semantic_documents_project_idx ON semantic_documents(project_id);
INSERT INTO schema_version(version) VALUES (1);
