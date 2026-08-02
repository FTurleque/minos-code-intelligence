package com.minos.packaging;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class M29DockerAdministrationContractTest {

    @Test
    void composeSeparatesReadOnlyQueryPlaneFromWritableEphemeralAdminPlane() throws Exception {
        String compose = normalizedText(repoRoot().resolve("docker/compose.mcp.prod.yaml"));
        String query = section(compose, "  minos-mcp:", "  minos-admin:");
        String admin = section(compose, "  minos-admin:", "  minos-bootstrap:");
        String bootstrap = section(compose, "  minos-bootstrap:", "  minos-tools-bootstrap:");
        String toolsBootstrap = compose.substring(compose.indexOf("  minos-tools-bootstrap:"));

        assertTrue(query.contains("io.minos.runtime-plane: query"));
        assertTrue(query.contains("target: /var/lib/minos\n        read_only: true"),
                "MCP query plane must mount MINOS business state read-only");
        assertTrue(query.contains("target: /var/lib/minos/tools\n        read_only: true"));
        assertTrue(query.contains("target: /workspace/projects\n        read_only: true"));

        assertTrue(admin.contains("io.minos.runtime-plane: admin"));
        assertTrue(admin.contains("target: /var/lib/minos"));
        assertFalse(admin.contains("target: /var/lib/minos\n        read_only: true"),
                "admin/indexing plane needs explicit write access to MINOS_HOME");
        assertTrue(admin.contains("target: /var/lib/minos/tools\n        read_only: true"),
                "provider tools must stay immutable during admin/indexing RUN");
        assertTrue(admin.contains("target: /workspace/projects\n        read_only: true"),
                "admin/indexing must never make project sources writable");
        assertTrue(admin.contains("com.minos.cli.MinosLauncher"));

        assertTrue(bootstrap.contains("com.minos.cli.DockerRuntimeBootstrap"));
        assertTrue(bootstrap.contains("configure-project-paths"));

        for (String plane : new String[]{query, admin, bootstrap, toolsBootstrap}) {
            assertTrue(plane.contains("network_mode: none"));
            assertTrue(plane.contains("read_only: true"));
            assertTrue(plane.contains("cap_drop:\n      - ALL"));
            assertTrue(plane.contains("no-new-privileges:true"));
        }
        assertTrue(query.contains("MINOS_RUNTIME_LOCATION: docker"));
        assertTrue(admin.contains("MINOS_RUNTIME_LOCATION: docker"));
        assertTrue(bootstrap.contains("MINOS_RUNTIME_LOCATION: docker"));
        assertTrue(toolsBootstrap.contains("io.minos.runtime-plane: tools-bootstrap"));
        assertTrue(toolsBootstrap.contains("cp -a /opt/minos/provider-tools/. /var/lib/minos/tools/"));
    }

    @Test
    void packagedWorkflowBootstrapsMappingProvidersAndExposesAdminAction() throws Exception {
        Path root = repoRoot();
        String workflow = normalizedText(root.resolve("docker/scripts/prod-mcp-release.ps1"));
        String dockerfile = normalizedText(root.resolve("docker/Dockerfile.mcp.release"));

        assertTrue(workflow.contains("'Admin'"));
        assertTrue(workflow.contains("'minos-bootstrap'"));
        assertTrue(workflow.contains("'minos-tools-bootstrap'"));
        assertTrue(workflow.contains("'minos-admin'"));
        assertTrue(workflow.contains("'tools', 'list', '--format', 'json'"));
        assertTrue(workflow.contains("'tools', 'verify', '--format', 'json'"));
        assertTrue(workflow.contains("provider-inventory.json"));
        assertTrue(workflow.contains("provider-binary-sha256.txt"));
        assertTrue(workflow.contains("MINOS_HOST_PROJECTS_ROOT"));
        assertTrue(workflow.contains("formatVersion = 4"));
        assertTrue(workflow.contains("'--volumes'"));

        assertTrue(dockerfile.contains("FROM eclipse-temurin:24-jdk"));
        assertTrue(dockerfile.contains("MINOS_RUNTIME_LOCATION=docker"));
        assertTrue(dockerfile.contains("SCIP_TYPESCRIPT_VERSION=0.4.0"));
        assertTrue(dockerfile.contains("SCIP_JAVA_VERSION=0.13.1"));
        assertTrue(dockerfile.contains("SCIP_PYTHON_VERSION=0.6.6"));
        assertTrue(dockerfile.contains("SCIP_CLANG_VERSION=0.4.0"));
        assertTrue(dockerfile.contains("SCIP_DOTNET_VERSION=0.2.14"));
        assertTrue(dockerfile.contains("SCIP_GO_VERSION=0.2.7"));
        assertTrue(dockerfile.contains("RUST_ANALYZER_RELEASE=2026-07-27"));
        assertTrue(dockerfile.contains("provider-evidence/provider-inventory.json"));
    }

    @Test
    void providerProcessPlansKeepGeneratedIndexesOutOfProjectRoots() throws Exception {
        Path root = repoRoot();
        for (String relative : new String[]{
                "minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/ScipTypeScriptProcessPlanFactory.java",
                "minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/ScipJavaProcessPlanFactory.java",
                "minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/ScipClangProcessPlanFactory.java",
                "minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/ScipDotnetProcessPlanFactory.java",
                "minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/ScipGoProcessPlanFactory.java",
                "minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/RustAnalyzerScipProcessPlanFactory.java"
        }) {
            String source = normalizedText(root.resolve(relative));
            assertTrue(source.contains("runDirectory"), relative + " must route output through the MINOS run directory");
            assertFalse(source.contains("root.resolve(\"index.scip\")"),
                    relative + " must not declare index.scip inside read-only project sources");
        }
    }

    @Test
    void s3RunnerFailsFastOnDockerAndExercisesRealLifecycleWhenAvailable() throws Exception {
        String runner = normalizedText(repoRoot().resolve("scripts/m29/run-s3.ps1"));

        assertTrue(runner.contains("M29-S3 exact-head mismatch"));
        assertTrue(runner.contains("status', '--porcelain"));
        assertTrue(runner.contains("Docker Desktop Linux daemon is unavailable"));
        assertTrue(runner.contains("'project', 'add'"));
        assertTrue(runner.contains("'index', 'm29-s3-fixture'"));
        assertFalse(runner.contains("'index', 'm29-s3-fixture', '--dry-run'"),
                "S3 gate must exercise a real provider/index lifecycle rather than only planning it");
        assertTrue(runner.contains("MinosNativeMcpSmoke.java"));
        assertTrue(runner.contains("Invoke-McpHandshake"));
        assertTrue(runner.contains("Invoke-Workflow -Action Start"));
        assertTrue(runner.contains("result = if ($Passed) { 'PASS' } else { 'FAIL_OR_BLOCKED' }"));
    }

    private static String normalizedText(Path path) throws IOException {
        return Files.readString(path).replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String section(String content, String start, String end) {
        int from = content.indexOf(start);
        int to = content.indexOf(end, from + start.length());
        assertTrue(from >= 0, "missing section " + start);
        assertTrue(to > from, "missing following section " + end);
        return content.substring(from, to);
    }

    private static Path repoRoot() throws IOException {
        Path candidate = Path.of("").toAbsolutePath().normalize();
        for (int i = 0; i < 5 && candidate != null; i++, candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("pom.xml"))
                    && Files.isRegularFile(candidate.resolve("docker/compose.mcp.prod.yaml"))) {
                return candidate;
            }
        }
        throw new IOException("repository root not found from " + Path.of("").toAbsolutePath());
    }
}
