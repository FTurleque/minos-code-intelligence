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
        String bootstrap = compose.substring(compose.indexOf("  minos-bootstrap:"));

        assertTrue(query.contains("io.minos.runtime-plane: query"));
        assertTrue(query.contains("target: /var/lib/minos\n        read_only: true"),
                "MCP query plane must mount MINOS business state read-only");
        assertTrue(query.contains("target: /workspace/projects\n        read_only: true"));

        assertTrue(admin.contains("io.minos.runtime-plane: admin"));
        assertTrue(admin.contains("target: /var/lib/minos"));
        assertFalse(admin.contains("target: /var/lib/minos\n        read_only: true"),
                "admin/indexing plane needs explicit write access to MINOS_HOME");
        assertTrue(admin.contains("target: /workspace/projects\n        read_only: true"),
                "admin/indexing must never make project sources writable");
        assertTrue(admin.contains("com.minos.cli.MinosLauncher"));

        assertTrue(bootstrap.contains("com.minos.cli.DockerRuntimeBootstrap"));
        assertTrue(bootstrap.contains("configure-project-paths"));

        for (String plane : new String[]{query, admin, bootstrap}) {
            assertTrue(plane.contains("network_mode: none"));
            assertTrue(plane.contains("read_only: true"));
            assertTrue(plane.contains("cap_drop:\n      - ALL"));
            assertTrue(plane.contains("no-new-privileges:true"));
            assertTrue(plane.contains("MINOS_RUNTIME_LOCATION: docker"));
        }
    }

    @Test
    void packagedWorkflowBootstrapsMappingAndExposesAdminAction() throws Exception {
        Path root = repoRoot();
        String workflow = normalizedText(root.resolve("docker/scripts/prod-mcp-release.ps1"));
        String dockerfile = normalizedText(root.resolve("docker/Dockerfile.mcp.release"));

        assertTrue(workflow.contains("'Admin'"));
        assertTrue(workflow.contains("'minos-bootstrap'"));
        assertTrue(workflow.contains("'minos-admin'"));
        assertTrue(workflow.contains("MINOS_HOST_PROJECTS_ROOT"));
        assertTrue(workflow.contains("formatVersion = 3"));
        assertTrue(dockerfile.contains("MINOS_RUNTIME_LOCATION=docker"));
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
