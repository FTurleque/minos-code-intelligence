package com.minos.packaging;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class M29DockerReleaseCiGateContractTest {

    @Test
    void permanentCiBuildsAndSmokesTheProviderCompleteImageAtExactHead() throws Exception {
        Path root = repoRoot();
        String workflow = normalizedText(root.resolve(".github/workflows/docker-release-validation.yml"));
        String qualification = normalizedText(root.resolve("scripts/ci/qualify-docker-release.sh"));
        String mcpSmoke = normalizedText(root.resolve("docker/scripts/MinosDockerMcpSmoke.java"));

        assertTrue(workflow.contains("pull_request:\n    branches: [main, develop]"));
        assertTrue(workflow.contains("push:\n    branches: [main, develop]"));
        assertFalse(workflow.contains("paths:"),
                "the provider-complete release gate must not silently skip transitive packaging changes");
        assertTrue(workflow.contains("github.event.pull_request.head.sha"));
        assertTrue(workflow.contains("fetch-depth: 0"));
        assertTrue(workflow.contains("bash scripts/ci/qualify-docker-release.sh \"$EXPECTED_SHA\""));
        assertTrue(workflow.contains("target/qualification/docker-release/**"));

        assertTrue(qualification.contains("ACTUAL_HEAD=\"$(git rev-parse HEAD)\""));
        assertTrue(qualification.contains("Docker release exact-head mismatch"));
        assertTrue(qualification.contains("./mvnw -B -ntp -DskipTests -DskipITs package"));
        assertTrue(qualification.contains("minos-code-intelligence-*-all.jar"));
        assertTrue(qualification.contains("scip-typescript-package-lock.json"));
        assertTrue(qualification.contains("scip-python-package-lock.json"));
        assertTrue(qualification.contains("docker build"),
                "qualification must execute a real Docker build, not only inspect Dockerfile text");
        assertTrue(qualification.contains("--file docker/Dockerfile.mcp.release"));
        assertTrue(qualification.contains("MINOS_GIT_COMMIT=$ACTUAL_HEAD"));
        assertTrue(qualification.contains("docker image inspect"));
        assertTrue(qualification.contains("org.opencontainers.image.revision"));
        assertTrue(qualification.contains("io.minos.providers.prepared"));
        assertTrue(qualification.contains("RUNTIME_USER=\"$(docker image inspect --format '{{.Config.User}}' \"$IMAGE\")\""));
        assertTrue(qualification.contains("ENTRYPOINT=\"$(docker image inspect --format '{{json .Config.Entrypoint}}' \"$IMAGE\")\""));
        assertTrue(qualification.contains("10001:10001"));
        assertTrue(qualification.contains("com.minos.mcp.MinosMcpServer"));
        assertTrue(qualification.contains("provider-inventory.json"));
        assertTrue(qualification.contains("provider-binary-sha256.txt"));
        assertTrue(qualification.contains("sha256sum -c /opt/minos/provider-evidence/binary-sha256.txt"));
        assertTrue(qualification.contains("com.minos.cli.MinosLauncher --help"));
        assertTrue(qualification.contains("java docker/scripts/MinosDockerMcpSmoke.java"),
                "Docker release qualification must reuse the production MCP protocol smoke client");
        assertTrue(qualification.contains("--network none --read-only"));
        assertTrue(qualification.contains(
                        "--tmpfs /var/lib/minos:rw,nosuid,nodev,noexec,size=64m,mode=700,uid=10001,gid=10001"),
                "MCP smoke writable state must remain owned by the non-root runtime user");

        int initializeResponse = mcpSmoke.indexOf("readResponse(outputReader, output, 1");
        int initializedNotification = mcpSmoke.indexOf("notifications/initialized");
        int toolsList = mcpSmoke.indexOf("tools/list");
        int toolsResponse = mcpSmoke.indexOf("readResponse(outputReader, output, 2");
        assertTrue(initializeResponse >= 0, "production MCP smoke must await initialize response");
        assertTrue(initializedNotification > initializeResponse,
                "notifications/initialized must only be sent after initialize succeeds");
        assertTrue(toolsList > initializedNotification,
                "tools/list must only be sent after notifications/initialized");
        assertTrue(toolsResponse > toolsList, "production MCP smoke must await tools/list response");
        assertTrue(mcpSmoke.contains("minos_impact"), "production MCP smoke must validate real MINOS tools");
    }

    private static String normalizedText(Path path) throws IOException {
        return Files.readString(path).replace("\r\n", "\n").replace('\r', '\n');
    }

    private static Path repoRoot() throws IOException {
        Path candidate = Path.of("").toAbsolutePath().normalize();
        for (int i = 0; i < 5 && candidate != null; i++, candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("pom.xml"))
                    && Files.isRegularFile(candidate.resolve("docker/Dockerfile.mcp.release"))) {
                return candidate;
            }
        }
        throw new IOException("repository root not found from " + Path.of("").toAbsolutePath());
    }
}
