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
        assertTrue(qualification.contains(
                "./mvnw -B -ntp -DskipTests -DskipITs \"-Dproject.build.outputTimestamp=$(git show -s --format=%cI HEAD)\" package"),
                "the image must embed a JAR built with the reproducible timestamp of the commit (AUD-DEP-11)");
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
        assertTrue(qualification.contains("chown 10001:10001 /var/lib/minos && chmod 700 /var/lib/minos"),
                "the MINOS data root of the qualification must be owned by the non-root runtime user");

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

    /**
     * AUD-DEP-16 — chaque image qui embarque le JAR en extrait les notices et licences vers
     * {@code /opt/minos/licenses}, échoue si l'extraction est vide, et la qualification compare le
     * contenu de l'image à celui du JAR.
     */
    @Test
    void everyImageExtractsTheNoticesAndLicencesOfTheJarAndTheQualificationComparesThem() throws Exception {
        Path root = repoRoot();
        String qualification = normalizedText(root.resolve("scripts/ci/qualify-docker-release.sh"));

        for (String name : new String[] {"docker/Dockerfile.mcp.release", "docker/Dockerfile.mcp"}) {
            String dockerfile = normalizedText(root.resolve(name));
            int extraction = dockerfile.indexOf("jar xf ");
            assertTrue(extraction >= 0, name + " must extract the notices and licences from the JAR it embeds");
            int endOfStep = dockerfile.indexOf("\n\n", extraction);
            String step = dockerfile.substring(extraction, endOfStep < 0 ? dockerfile.length() : endOfStep);
            assertTrue(step.contains("META-INF/NOTICE META-INF/LICENSE META-INF/licenses/"),
                    name + " must extract the aggregated notice, the MINOS licence and the per-dependency licences");
            assertTrue(step.contains("test -s META-INF/NOTICE") && step.contains("test -s META-INF/LICENSE")
                            && step.contains("find META-INF/licenses -type f -size +0c -print -quit"),
                    name + " must fail the build when the extraction produces nothing");
            assertTrue(dockerfile.contains("/opt/minos/licenses"), name + " must ship the documented licences directory");
            assertTrue(extraction < dockerfile.lastIndexOf("USER 10001:10001"),
                    name + " must extract the licences before dropping to the runtime user");
        }

        int build = qualification.indexOf("docker build");
        int licences = qualification.indexOf("/opt/minos/licenses");
        assertTrue(licences > build, "qualification must read the licences directory of the image it has just built");
        assertTrue(qualification.contains("sha256sum"), "qualification must hash the licence files of the image");
        assertTrue(qualification.contains("image-licenses-sha256.txt"),
                "qualification must keep the licence hashes of the image as evidence");
        assertTrue(qualification.contains("\"META-INF/NOTICE\"") && qualification.contains("\"META-INF/licenses/\""),
                "qualification must compare the notices and licences of the image with those of the shaded JAR");
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
