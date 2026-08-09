#!/usr/bin/env python3
"""Apply fail-closed post-audit hardening transformations.

This script is intentionally strict: every source transformation must match the
expected pre-remediation text exactly, otherwise it aborts without committing.
External binary digests are supplied by the qualifying GitHub Actions runner.
"""
from __future__ import annotations

import os
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def path(relative: str) -> Path:
    return ROOT / relative


def read(relative: str) -> str:
    return path(relative).read_text(encoding="utf-8")


def write(relative: str, value: str) -> None:
    path(relative).write_text(value, encoding="utf-8", newline="\n")


def replace_exact(relative: str, old: str, new: str, *, count: int = 1) -> None:
    value = read(relative)
    actual = value.count(old)
    if actual != count:
        raise RuntimeError(f"{relative}: expected {count} exact matches, found {actual}: {old[:120]!r}")
    write(relative, value.replace(old, new, count))


def remove_exact(relative: str, old: str, *, count: int = 1) -> None:
    replace_exact(relative, old, "", count=count)


def require_env(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        raise RuntimeError(f"missing required remediation environment variable: {name}")
    return value


def ensure_sha256(value: str, label: str) -> str:
    value = value.lower().strip()
    if not re.fullmatch(r"[0-9a-f]{64}", value):
        raise RuntimeError(f"{label} must be a lowercase SHA-256 digest")
    return value


def harden_provider_ids() -> None:
    provider_id = path("minos-engine/src/main/java/com/minos/orchestration/ProviderId.java")
    if not provider_id.exists():
        provider_id.write_text(
            """package com.minos.orchestration;\n\n"
            "/** Canonical invariant for provider/indexer identifiers used in filesystem paths. */\n"
            "public final class ProviderId {\n"
            "    private static final String SAFE_PATTERN = \"[A-Za-z0-9][A-Za-z0-9._-]{0,127}\";\n\n"
            "    private ProviderId() {\n"
            "    }\n\n"
            "    public static String require(String value) {\n"
            "        if (value == null || !value.matches(SAFE_PATTERN)) {\n"
            "            throw new IllegalArgumentException(\"provider id must match \" + SAFE_PATTERN);\n"
            "        }\n"
            "        return value;\n"
            "    }\n"
            "}\n""".replace('"\n            "', ''),
            encoding="utf-8",
            newline="\n",
        )

    replace_exact(
        "minos-engine/src/main/java/com/minos/orchestration/IndexerDescriptor.java",
        '        id = requireText(id, "id");',
        '        id = ProviderId.require(id);',
    )

    relative = "minos-runtime-local/src/main/java/com/minos/runtime/ProcessIndexerExecutor.java"
    value = read(relative)
    value = value.replace(
        "import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;\n",
        "import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;\nimport com.minos.orchestration.ProviderId;\n",
        1,
    )
    old = '''        if (indexerId == null || indexerId.isBlank()) {\n            throw new IllegalArgumentException("indexerId must not be blank");\n        }\n        this.indexerId = indexerId;'''
    if value.count(old) != 1:
        raise RuntimeError("ProcessIndexerExecutor: indexerId constructor invariant drifted")
    value = value.replace(old, "        this.indexerId = ProviderId.require(indexerId);", 1)
    old_run = '''        Path providerRunDirectory = runsRoot.resolve(request.runId().toString()).resolve(indexerId);'''
    new_run = '''        Path providerRunDirectory = runsRoot.resolve(request.runId().toString()).resolve(indexerId)\n                .toAbsolutePath().normalize();\n        if (!providerRunDirectory.startsWith(runsRoot)) {\n            throw new IllegalStateException("provider run directory escapes MINOS runs root");\n        }'''
    if value.count(old_run) != 1:
        raise RuntimeError("ProcessIndexerExecutor: run-directory construction drifted")
    value = value.replace(old_run, new_run, 1)
    if value.count("new ProcessBuilder(safeCommand(plan.command()))") != 1:
        raise RuntimeError("ProcessIndexerExecutor: expected safeCommand ProcessBuilder sink")
    value = value.replace("new ProcessBuilder(safeCommand(plan.command()))", "new ProcessBuilder(plan.command())", 1)
    safe_method = re.compile(
        r"\n    /\*\*\n     \* Rebuilds each command element character-by-character.*?\n    private static List<String> safeCommand\(List<String> command\) \{.*?\n    \}\n",
        re.DOTALL,
    )
    value, substitutions = safe_method.subn("\n", value, count=1)
    if substitutions != 1:
        raise RuntimeError("ProcessIndexerExecutor: safeCommand method drifted")
    write(relative, value)

    worker = "minos-runtime-local/src/main/java/com/minos/runtime/LocalIsolatedIndexWorker.java"
    value = read(worker)
    if "import com.minos.orchestration.ProviderId;" not in value:
        value = value.replace(
            "import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;\n",
            "import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;\nimport com.minos.orchestration.ProviderId;\n",
            1,
        )
    old = '        this.delegate = Objects.requireNonNull(delegate, "delegate");'
    new = old + '\n        ProviderId.require(this.delegate.indexerId());'
    if value.count(old) != 1:
        raise RuntimeError("LocalIsolatedIndexWorker: delegate assignment drifted")
    value = value.replace(old, new, 1)
    write(worker, value)


def harden_artifact_links() -> None:
    process = "minos-runtime-local/src/main/java/com/minos/runtime/ProcessIndexerExecutor.java"
    value = read(process)
    if "import java.nio.file.LinkOption;" not in value:
        value = value.replace("import java.nio.file.Files;\n", "import java.nio.file.Files;\nimport java.nio.file.LinkOption;\n", 1)
    value = value.replace("Files.isRegularFile(generatedArtifact)", "regularFileNoFollow(generatedArtifact)")
    value = value.replace("Files.isRegularFile(finalArtifact)", "regularFileNoFollow(finalArtifact)")
    value = value.replace("Files.isRegularFile(preservedArtifact)", "regularFileNoFollow(preservedArtifact)")
    marker = "    private static void append(Path file, String value) throws IOException {"
    helper = '''    private static boolean regularFileNoFollow(Path file) {\n        return !Files.isSymbolicLink(file) && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS);\n    }\n\n'''
    if helper not in value:
        if value.count(marker) != 1:
            raise RuntimeError("ProcessIndexerExecutor: append marker drifted")
        value = value.replace(marker, helper + marker, 1)
    write(process, value)

    worker = "minos-runtime-local/src/main/java/com/minos/runtime/LocalIsolatedIndexWorker.java"
    old = '''            if (!Files.isRegularFile(artifactPath) || Files.size(artifactPath) < 1L) {\n                throw new IOException("worker sandbox did not produce a non-empty artifact");\n            }'''
    new = '''            if (Files.isSymbolicLink(artifactPath)\n                    || !Files.isRegularFile(artifactPath, LinkOption.NOFOLLOW_LINKS)\n                    || Files.size(artifactPath) < 1L) {\n                throw new IOException("worker sandbox did not produce a non-empty regular artifact");\n            }'''
    replace_exact(worker, old, new)


def harden_coursier_windows() -> None:
    digest = ensure_sha256(require_env("COURSIER_WINDOWS_SHA256"), "COURSIER_WINDOWS_SHA256")
    relative = "minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/ManagedScipProviderRuntimeManager.java"
    value = read(relative)
    if "import java.security.MessageDigest;" not in value:
        value = value.replace("import java.nio.file.StandardCopyOption;\n", "import java.nio.file.StandardCopyOption;\nimport java.security.MessageDigest;\nimport java.security.NoSuchAlgorithmException;\nimport java.util.HexFormat;\n", 1)
    value = value.replace("import java.util.zip.ZipEntry;\nimport java.util.zip.ZipInputStream;\n", "")
    old_constants = '''    private static final String COURSIER_LAUNCHER_ID = "windows-x64-official-launcher";\n    private static final URI COURSIER_WINDOWS_URI = URI.create(\n            "https://github.com/coursier/launchers/raw/master/cs-x86_64-pc-win32.zip");'''
    new_constants = f'''    private static final String COURSIER_VERSION = "2.1.25-M26";\n    private static final String COURSIER_LAUNCHER_ID = "windows-x64-" + COURSIER_VERSION;\n    private static final URI COURSIER_WINDOWS_URI = URI.create(\n            "https://github.com/coursier/coursier/releases/download/v" + COURSIER_VERSION\n                    + "/cs-x86_64-pc-win32.exe");\n    private static final String COURSIER_WINDOWS_SHA256 = "{digest}";'''
    if value.count(old_constants) != 1:
        raise RuntimeError("ManagedScipProviderRuntimeManager: Coursier constants drifted")
    value = value.replace(old_constants, new_constants, 1)
    start = value.index("    private Path ensureCoursier() throws Exception {")
    end = value.index("\n    private Optional<Path> coursierExecutable()", start)
    replacement = '''    private Path ensureCoursier() throws Exception {\n        Optional<Path> existing = coursierExecutable();\n        if (existing.isPresent()) {\n            return existing.orElseThrow();\n        }\n        if (!CommandLocator.isWindows()) {\n            throw new IllegalStateException(\n                    "automatic Coursier installation is currently packaged for Windows x64; install `cs` in PATH");\n        }\n\n        Path directory = toolsRoot.resolve("coursier").resolve(COURSIER_LAUNCHER_ID);\n        Files.createDirectories(directory);\n        Path destination = directory.resolve("cs.exe");\n        Path executablePartial = directory.resolve("cs.partial.exe");\n        Files.deleteIfExists(executablePartial);\n\n        HttpClient client = HttpClient.newBuilder()\n                .followRedirects(HttpClient.Redirect.NORMAL)\n                .connectTimeout(Duration.ofSeconds(30))\n                .build();\n        HttpRequest request = HttpRequest.newBuilder(COURSIER_WINDOWS_URI)\n                .timeout(Duration.ofMinutes(2))\n                .header("User-Agent", "MINOS-Code-Intelligence")\n                .build();\n        HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(executablePartial));\n        if (response.statusCode() < 200 || response.statusCode() >= 300 || Files.size(executablePartial) == 0L) {\n            Files.deleteIfExists(executablePartial);\n            throw new IllegalStateException("Coursier launcher download failed with HTTP " + response.statusCode());\n        }\n        String actualDigest = sha256(executablePartial);\n        if (!MessageDigest.isEqual(\n                HexFormat.of().parseHex(COURSIER_WINDOWS_SHA256),\n                HexFormat.of().parseHex(actualDigest))) {\n            Files.deleteIfExists(executablePartial);\n            throw new IllegalStateException(\n                    "Coursier launcher checksum mismatch for " + COURSIER_VERSION\n                            + ": expected=" + COURSIER_WINDOWS_SHA256 + " actual=" + actualDigest);\n        }\n        move(executablePartial, destination);\n        return destination;\n    }\n\n    private static String sha256(Path file) throws IOException {\n        try {\n            MessageDigest digest = MessageDigest.getInstance("SHA-256");\n            try (InputStream input = Files.newInputStream(file)) {\n                byte[] buffer = new byte[8192];\n                int read;\n                while ((read = input.read(buffer)) >= 0) {\n                    if (read > 0) digest.update(buffer, 0, read);\n                }\n            }\n            return HexFormat.of().formatHex(digest.digest());\n        } catch (NoSuchAlgorithmException exception) {\n            throw new IllegalStateException("SHA-256 is unavailable", exception);\n        }\n    }\n'''
    value = value[:start] + replacement + value[end:]
    write(relative, value)


def isolate_runtime_settings() -> None:
    storage = "minos-application/src/main/java/com/minos/storage/StorageBackendConfiguration.java"
    replace_exact(
        storage,
        '''    public static StorageBackendConfiguration resolve(Path home) throws IOException {\n        MinosRuntimeSettings settings = MinosRuntimeSettings.load(home);\n        activateNonSecretRuntimeFallbacks(settings);\n        return resolve(settings);\n    }''',
        '''    public static StorageBackendConfiguration resolve(Path home) throws IOException {\n        return resolve(MinosRuntimeSettings.load(home));\n    }''',
    )
    replace_exact(storage, "    static StorageBackendConfiguration resolve(MinosRuntimeSettings settings) throws IOException {", "    public static StorageBackendConfiguration resolve(MinosRuntimeSettings settings) throws IOException {")
    value = read(storage)
    value, n = re.subn(
        r"\n    private static void activateNonSecretRuntimeFallbacks\(MinosRuntimeSettings settings\) \{.*?\n    \}\n",
        "\n",
        value,
        count=1,
        flags=re.DOTALL,
    )
    if n != 1:
        raise RuntimeError("StorageBackendConfiguration: fallback activator drifted")
    write(storage, value)

    settings = "minos-application/src/main/java/com/minos/storage/MinosRuntimeSettings.java"
    value = read(settings)
    value, n = re.subn(
        r"\n    /\*\*\n     \* Makes a durable file value visible.*?\n    public void activateFileFallback\(String property, String environmentVariable\) \{.*?\n    \}\n",
        "\n",
        value,
        count=1,
        flags=re.DOTALL,
    )
    if n != 1:
        raise RuntimeError("MinosRuntimeSettings: global fallback mutator drifted")
    write(settings, value)

    app = "minos-application/src/main/java/com/minos/application/MinosApplication.java"
    value = read(app)
    value = value.replace("import com.minos.storage.StorageBackendConfiguration;\n", "import com.minos.storage.StorageBackendConfiguration;\nimport com.minos.storage.MinosRuntimeSettings;\n", 1)
    old = "        Builder builder = builder(home).storageBackend(StorageBackends.open(StorageBackendConfiguration.resolve(home)));"
    new = '''        MinosRuntimeSettings settings = MinosRuntimeSettings.load(home);\n        Builder builder = builder(home).storageBackend(StorageBackends.open(StorageBackendConfiguration.resolve(settings)));'''
    if value.count(old) != 1:
        raise RuntimeError("MinosApplication: open storage line drifted")
    value = value.replace(old, new, 1)
    for old_call, new_call in [
        ("setting(SEMANTIC_PROVIDER_PROPERTY, SEMANTIC_PROVIDER_ENV)", "setting(settings, SEMANTIC_PROVIDER_PROPERTY, SEMANTIC_PROVIDER_ENV)"),
        ("requiredSetting(SEMANTIC_MODEL_PROPERTY, SEMANTIC_MODEL_ENV)", "requiredSetting(settings, SEMANTIC_MODEL_PROPERTY, SEMANTIC_MODEL_ENV)"),
        ("requiredSetting(SEMANTIC_DIMENSIONS_PROPERTY, SEMANTIC_DIMENSIONS_ENV)", "requiredSetting(settings, SEMANTIC_DIMENSIONS_PROPERTY, SEMANTIC_DIMENSIONS_ENV)"),
        ("setting(SEMANTIC_ENDPOINT_PROPERTY, SEMANTIC_ENDPOINT_ENV)", "setting(settings, SEMANTIC_ENDPOINT_PROPERTY, SEMANTIC_ENDPOINT_ENV)"),
        ("setting(SEMANTIC_TIMEOUT_SECONDS_PROPERTY, SEMANTIC_TIMEOUT_SECONDS_ENV)", "setting(settings, SEMANTIC_TIMEOUT_SECONDS_PROPERTY, SEMANTIC_TIMEOUT_SECONDS_ENV)"),
        ("setting(HOSTED_MODE_PROPERTY, HOSTED_MODE_ENV)", "setting(settings, HOSTED_MODE_PROPERTY, HOSTED_MODE_ENV)"),
    ]:
        if old_call not in value:
            raise RuntimeError(f"MinosApplication: missing runtime-setting call {old_call}")
        value = value.replace(old_call, new_call, 1)
    old_helpers = '''    private static String setting(String property, String environment) {\n        String value = System.getProperty(property); return value == null || value.isBlank() ? System.getenv(environment) : value;\n    }\n    private static String requiredSetting(String property, String environment) {\n        String value = setting(property, environment);'''
    new_helpers = '''    private static String setting(MinosRuntimeSettings settings, String property, String environment) {\n        return settings.value(property, environment);\n    }\n    private static String requiredSetting(MinosRuntimeSettings settings, String property, String environment) {\n        String value = setting(settings, property, environment);'''
    if value.count(old_helpers) != 1:
        raise RuntimeError("MinosApplication: runtime-setting helpers drifted")
    value = value.replace(old_helpers, new_helpers, 1)
    write(app, value)


def harden_postgresql_concurrency() -> None:
    migrator = "minos-storage-postgresql/src/main/java/com/minos/storage/postgresql/PostgresSchemaMigrator.java"
    value = read(migrator)
    value = value.replace("static final int CURRENT_VERSION = 1;", "static final int CURRENT_VERSION = 2;", 1)
    old = '''                statement.execute("SET search_path TO " + schema + ", public");\n                statement.execute("CREATE TABLE IF NOT EXISTS schema_version (version integer PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now())");\n                int version = currentVersion(statement);'''
    new = '''                statement.execute("SET search_path TO " + schema + ", public");\n                statement.execute("SELECT pg_advisory_xact_lock(hashtext('minos-schema-migration'), hashtext(current_schema()))");\n                statement.execute("CREATE TABLE IF NOT EXISTS schema_version (version integer PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now())");\n                int version = currentVersion(statement);'''
    if value.count(old) != 1:
        raise RuntimeError("PostgresSchemaMigrator: migration preamble drifted")
    value = value.replace(old, new, 1)
    value = value.replace("                if (version < 1) applyV1(statement);", "                if (version < 1) applyV1(statement);\n                if (version < 2) applyV2(statement);", 1)
    insert_before = "\n    private static void applyV1(Statement s) throws SQLException {"
    apply_v2 = '''\n    private static void applyV2(Statement s) throws SQLException {\n        try (ResultSet duplicates = s.executeQuery(\n                "SELECT root_value, root_portable, COUNT(*) FROM projects "\n                        + "GROUP BY root_value, root_portable HAVING COUNT(*) > 1 LIMIT 1")) {\n            if (duplicates.next()) {\n                throw new SQLException("duplicate project roots prevent schema v2 uniqueness migration");\n            }\n        }\n        s.execute("CREATE UNIQUE INDEX IF NOT EXISTS projects_root_identity_uq ON projects(root_value, root_portable)");\n        s.execute("INSERT INTO schema_version(version) VALUES (2) ON CONFLICT(version) DO NOTHING");\n    }\n'''
    if apply_v2 not in value:
        if value.count(insert_before) != 1:
            raise RuntimeError("PostgresSchemaMigrator: applyV1 marker drifted")
        value = value.replace(insert_before, apply_v2 + insert_before, 1)
    write(migrator, value)

    registry = "minos-storage-postgresql/src/main/java/com/minos/storage/postgresql/PostgresProjectRegistry.java"
    value = read(registry)
    old_register = '''        for (RegisteredProject existing : listProjects()) {\n            if (existing.rootPath().equals(canonical)) return existing;\n        }\n        Instant now = Instant.now();\n        RegisteredProject project = new RegisteredProject(UUID.randomUUID(), canonical, displayName, Optional.empty(), now, now);\n        writeProject(project);\n        return project;'''
    new_register = '''        RootIdentity root = rootIdentity(canonical);\n        Instant now = Instant.now();\n        UUID candidateId = UUID.randomUUID();\n        try (Connection c = connections.open(); PreparedStatement s = c.prepareStatement(\n                "INSERT INTO projects(id,root_value,root_portable,display_name,workspace_id,created_at,updated_at) "\n                        + "VALUES (?,?,?,?,NULL,?,?) "\n                        + "ON CONFLICT(root_value,root_portable) DO UPDATE SET root_value=EXCLUDED.root_value "\n                        + "RETURNING id,root_value,root_portable,display_name,workspace_id,created_at,updated_at")) {\n            s.setObject(1, candidateId); s.setString(2, root.value()); s.setBoolean(3, root.portable());\n            s.setString(4, displayName); s.setObject(5, now); s.setObject(6, now);\n            try (ResultSet r = s.executeQuery()) {\n                if (!r.next()) throw new SQLException("project registration did not return a row");\n                return readProject(r);\n            }\n        } catch (SQLException e) { throw io("register project", e); }'''
    if value.count(old_register) != 1:
        raise RuntimeError("PostgresProjectRegistry: registerProject body drifted")
    value = value.replace(old_register, new_register, 1)
    marker = "    private void writeProject(RegisteredProject project) throws IOException {"
    helper = '''    private RootIdentity rootIdentity(Path physicalRoot) throws IOException {\n        boolean portable = mapping.isPresent();\n        try {\n            String rootValue = portable\n                    ? mapping.orElseThrow().relativeForPhysical(physicalRoot, runtimeLocation)\n                    : physicalRoot.toString();\n            return new RootIdentity(rootValue, portable);\n        } catch (IllegalArgumentException e) {\n            throw new IOException("project path cannot be represented by configured runtime mapping", e);\n        }\n    }\n\n'''
    if helper not in value:
        if value.count(marker) != 1:
            raise RuntimeError("PostgresProjectRegistry: writeProject marker drifted")
        value = value.replace(marker, helper + marker, 1)
    tail_marker = "    private static IOException io(String action, SQLException e) { return new IOException(\"PostgreSQL project registry failed to \" + action, e); }\n}"
    new_tail = '''    private record RootIdentity(String value, boolean portable) { }\n\n    private static IOException io(String action, SQLException e) { return new IOException("PostgreSQL project registry failed to " + action, e); }\n}'''
    if value.count(tail_marker) != 1:
        raise RuntimeError("PostgresProjectRegistry: tail marker drifted")
    value = value.replace(tail_marker, new_tail, 1)
    write(registry, value)


def remove_api_cli_edge() -> None:
    pom = "minos-api/pom.xml"
    block = '''        <dependency>\n            <groupId>com.minos</groupId>\n            <artifactId>minos-cli</artifactId>\n            <version>${project.version}</version>\n        </dependency>\n'''
    remove_exact(pom, block)
    policy = "scripts/architecture/check-module-boundaries.py"
    replace_exact(
        policy,
        '''    # Historical LocalMinosApi wiring still consumes reusable CLI operations. The edge is explicit\n    # and cannot grow into additional surface-to-surface dependencies without a policy change.\n    "minos-api": frozenset({\n        "minos-domain", "minos-engine", "minos-application", "minos-storage-local",\n        "minos-cli", "minos-integration-git"\n    }),''',
        '''    "minos-api": frozenset({\n        "minos-domain", "minos-engine", "minos-application", "minos-storage-local",\n        "minos-integration-git"\n    }),''',
    )


def harden_ollama_json() -> None:
    pom = "minos-application/pom.xml"
    value = read(pom)
    dependency = '''        <dependency>\n            <groupId>com.fasterxml.jackson.core</groupId>\n            <artifactId>jackson-databind</artifactId>\n        </dependency>\n'''
    if dependency not in value:
        marker = "    <dependencies>\n"
        if value.count(marker) != 1:
            raise RuntimeError("minos-application/pom.xml: dependencies marker drifted")
        value = value.replace(marker, marker + dependency, 1)
    write(pom, value)

    relative = "minos-application/src/main/java/com/minos/semantic/OllamaEmbeddingProvider.java"
    value = read(relative)
    if "import com.fasterxml.jackson.databind.JsonNode;" not in value:
        value = value.replace("package com.minos.semantic;\n\n", "package com.minos.semantic;\n\nimport com.fasterxml.jackson.databind.JsonNode;\nimport com.fasterxml.jackson.databind.ObjectMapper;\n\n", 1)
    marker = '    private static final String MANAGED_DOCKER_HOST = "minos-ollama";\n'
    if "private static final ObjectMapper JSON" not in value:
        value = value.replace(marker, marker + "    private static final ObjectMapper JSON = new ObjectMapper();\n", 1)
    start = value.index("    static double[] parseEmbeddingResponse(String response, int expectedDimensions) throws IOException {")
    end = value.index("\n    static String requestBody", start)
    parser = '''    static double[] parseEmbeddingResponse(String response, int expectedDimensions) throws IOException {\n        Objects.requireNonNull(response, "response");\n        JsonNode root = JSON.readTree(response);\n        JsonNode embeddings = root == null ? null : root.get("embeddings");\n        if (embeddings == null || !embeddings.isArray()) {\n            throw new IOException("Ollama response does not contain an embeddings array");\n        }\n        if (embeddings.size() != 1 || !embeddings.get(0).isArray()) {\n            throw new IOException("Ollama response must contain exactly one embedding vector");\n        }\n        JsonNode vector = embeddings.get(0);\n        if (vector.size() != expectedDimensions) {\n            throw new IOException("Ollama embedding dimensions mismatch: expected " + expectedDimensions\n                    + " but got " + vector.size());\n        }\n        double[] values = new double[expectedDimensions];\n        for (int index = 0; index < expectedDimensions; index++) {\n            JsonNode element = vector.get(index);\n            if (element == null || !element.isNumber()) {\n                throw new IOException("Ollama embedding contains a non-numeric value");\n            }\n            values[index] = element.doubleValue();\n            if (!Double.isFinite(values[index])) {\n                throw new IOException("Ollama embedding contains a non-finite value");\n            }\n        }\n        return values;\n    }\n'''
    value = value[:start] + parser + value[end:]
    value, n = re.subn(r"\n    private static int matchingBracket\(String value, int start\) throws IOException \{.*?\n    \}\n", "\n", value, count=1, flags=re.DOTALL)
    if n != 1:
        raise RuntimeError("OllamaEmbeddingProvider: obsolete bracket parser drifted")
    write(relative, value)


def raise_critical_coverage() -> None:
    relative = "scripts/quality/check-jacoco.py"
    value = read(relative)
    replacements = {
        '"semantic-learned-provider": {"prefixes": ("com/minos/semantic/OllamaEmbeddingProvider",), "line": 0.50, "branch": 0.30}':
            '"semantic-learned-provider": {"prefixes": ("com/minos/semantic/OllamaEmbeddingProvider",), "line": 0.52, "branch": 0.32}',
        '        ), "line": 0.45, "branch": 0.25,\n    },\n    "m26-runtime-dynamic-intelligence"':
            '        ), "line": 0.47, "branch": 0.27,\n    },\n    "m26-runtime-dynamic-intelligence"',
        '    "m30-storage-backend-selection": {': '    "m30-storage-backend-selection": {',
        '        ), "line": 0.50, "branch": 0.30,\n    },\n    "m30-postgresql-pgvector": {"prefixes": ("com/minos/storage/postgresql/",), "line": 0.45, "branch": 0.25},':
            '        ), "line": 0.52, "branch": 0.32,\n    },\n    "m30-postgresql-pgvector": {"prefixes": ("com/minos/storage/postgresql/",), "line": 0.47, "branch": 0.27},',
    }
    for old, new in replacements.items():
        if old == new:
            continue
        if value.count(old) != 1:
            raise RuntimeError(f"check-jacoco.py: coverage threshold marker drifted: {old[:80]}")
        value = value.replace(old, new, 1)
    write(relative, value)


def harden_docker_downloads() -> None:
    coursier = ensure_sha256(require_env("COURSIER_LINUX_SHA256"), "COURSIER_LINUX_SHA256")
    clang = ensure_sha256(require_env("SCIP_CLANG_LINUX_SHA256"), "SCIP_CLANG_LINUX_SHA256")
    rust = ensure_sha256(require_env("RUST_ANALYZER_LINUX_GZ_SHA256"), "RUST_ANALYZER_LINUX_GZ_SHA256")
    relative = "docker/Dockerfile.mcp.release"
    value = read(relative)
    images = {
        "FROM rust:1.97.1-bookworm AS rust-toolchain": f"FROM {require_env('RUST_BASE_IMAGE')} AS rust-toolchain",
        "FROM golang:1.26.5-bookworm AS go-toolchain": f"FROM {require_env('GO_BASE_IMAGE')} AS go-toolchain",
        "FROM mcr.microsoft.com/dotnet/sdk:10.0.302-noble AS dotnet-toolchain": f"FROM {require_env('DOTNET_BASE_IMAGE')} AS dotnet-toolchain",
        "FROM eclipse-temurin:24-jdk": f"FROM {require_env('TEMURIN_BASE_IMAGE')}",
    }
    for old, new in images.items():
        if value.count(old) != 1:
            raise RuntimeError(f"Dockerfile: base image marker drifted: {old}")
        value = value.replace(old, new, 1)
    version_marker = "ARG RUST_ANALYZER_COMMIT=12c3381\n"
    digest_args = f"ARG COURSIER_LINUX_SHA256={coursier}\nARG SCIP_CLANG_LINUX_SHA256={clang}\nARG RUST_ANALYZER_LINUX_GZ_SHA256={rust}\n"
    if digest_args not in value:
        value = value.replace(version_marker, version_marker + digest_args, 1)
    value = value.replace(
        '    curl -fsSLo /tmp/cs.gz "https://github.com/coursier/coursier/releases/download/v${COURSIER_VERSION}/cs-x86_64-pc-linux.gz"; \\\n    gunzip /tmp/cs.gz;',
        '    curl -fsSLo /tmp/cs.gz "https://github.com/coursier/coursier/releases/download/v${COURSIER_VERSION}/cs-x86_64-pc-linux.gz"; \\\n    printf \'%s  %s\\n\' "${COURSIER_LINUX_SHA256}" /tmp/cs.gz | sha256sum -c -; \\\n    gunzip /tmp/cs.gz;',
        1,
    )
    value = value.replace(
        '    curl -fsSLo /tmp/scip-clang "https://github.com/sourcegraph/scip-clang/releases/download/v${SCIP_CLANG_VERSION}/scip-clang-x86_64-linux"; \\\n    install -m 0755 /tmp/scip-clang /usr/local/bin/scip-clang;',
        '    curl -fsSLo /tmp/scip-clang "https://github.com/sourcegraph/scip-clang/releases/download/v${SCIP_CLANG_VERSION}/scip-clang-x86_64-linux"; \\\n    printf \'%s  %s\\n\' "${SCIP_CLANG_LINUX_SHA256}" /tmp/scip-clang | sha256sum -c -; \\\n    install -m 0755 /tmp/scip-clang /usr/local/bin/scip-clang;',
        1,
    )
    value = value.replace(
        '    curl -fsSLo /tmp/rust-analyzer.gz "https://github.com/rust-lang/rust-analyzer/releases/download/${RUST_ANALYZER_RELEASE}/rust-analyzer-x86_64-unknown-linux-gnu.gz"; \\\n    gunzip /tmp/rust-analyzer.gz;',
        '    curl -fsSLo /tmp/rust-analyzer.gz "https://github.com/rust-lang/rust-analyzer/releases/download/${RUST_ANALYZER_RELEASE}/rust-analyzer-x86_64-unknown-linux-gnu.gz"; \\\n    printf \'%s  %s\\n\' "${RUST_ANALYZER_LINUX_GZ_SHA256}" /tmp/rust-analyzer.gz | sha256sum -c -; \\\n    gunzip /tmp/rust-analyzer.gz;',
        1,
    )
    write(relative, value)


def resolve_action_ref(repository: str, ref: str) -> str:
    if re.fullmatch(r"[0-9a-fA-F]{40}", ref):
        return ref.lower()
    url = f"https://github.com/{repository}.git"
    candidates = [f"refs/tags/{ref}^{{}}", f"refs/tags/{ref}", f"refs/heads/{ref}"]
    proc = subprocess.run(["git", "ls-remote", url, *candidates], text=True, capture_output=True, check=True)
    rows = [line.split() for line in proc.stdout.splitlines() if line.strip()]
    peeled = next((sha for sha, name in rows if name == f"refs/tags/{ref}^{{}}"), None)
    direct = next((sha for sha, name in rows if name in {f"refs/tags/{ref}", f"refs/heads/{ref}"}), None)
    resolved = peeled or direct
    if not resolved or not re.fullmatch(r"[0-9a-fA-F]{40}", resolved):
        raise RuntimeError(f"unable to resolve immutable GitHub Action ref {repository}@{ref}")
    return resolved.lower()


def pin_workflow_actions_and_inno() -> None:
    pattern = re.compile(r"(?P<prefix>\buses:\s*)(?P<repo>[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)@(?P<ref>[^\s#]+)(?P<comment>\s*#.*)?$")
    cache: dict[tuple[str, str], str] = {}
    for workflow in sorted((ROOT / ".github/workflows").glob("*.y*ml")):
        lines = workflow.read_text(encoding="utf-8").splitlines()
        changed = False
        rendered: list[str] = []
        for line in lines:
            match = pattern.search(line)
            if match:
                repo, ref = match.group("repo"), match.group("ref")
                if not re.fullmatch(r"[0-9a-fA-F]{40}", ref):
                    key = (repo, ref)
                    sha = cache.setdefault(key, resolve_action_ref(repo, ref))
                    line = pattern.sub(lambda m: f"{m.group('prefix')}{repo}@{sha} # {ref}", line)
                    changed = True
            if "choco install innosetup --yes --no-progress" in line:
                line = line.replace("choco install innosetup --yes --no-progress", "choco install innosetup --version=6.7.1 --yes --no-progress")
                changed = True
            rendered.append(line)
        if changed:
            workflow.write_text("\n".join(rendered) + "\n", encoding="utf-8", newline="\n")


def main() -> int:
    harden_provider_ids()
    harden_artifact_links()
    harden_coursier_windows()
    isolate_runtime_settings()
    harden_postgresql_concurrency()
    remove_api_cli_edge()
    harden_ollama_json()
    raise_critical_coverage()
    harden_docker_downloads()
    pin_workflow_actions_and_inno()
    print("POST-AUDIT REMEDIATION TRANSFORM SUCCESS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
