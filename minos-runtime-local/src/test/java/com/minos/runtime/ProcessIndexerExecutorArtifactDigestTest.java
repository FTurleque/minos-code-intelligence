package com.minos.runtime;

import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;
import com.minos.orchestration.IndexerQualification;
import com.minos.orchestration.IndexingMode;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R1 lot 1 : le SHA-256 de l'artefact est calculé à la promotion et écrit durablement à côté (ADR 0039 §1). */
class ProcessIndexerExecutorArtifactDigestTest {

    private static final String PROVIDER = "fake-provider";

    @TempDir
    Path temporary;

    @Test
    void promotionWritesADigestSidecarMatchingTheFinalArtifact() throws Exception {
        Path project = Files.createDirectories(temporary.resolve("project"));
        Path generated = project.resolve("index.scip");
        Path source = writeProvider("DigestProvider");
        String java = javaExecutable();
        ProcessIndexerExecutor executor = new ProcessIndexerExecutor(PROVIDER, temporary.resolve("home"),
                (request, runDirectory) -> new IndexerProcessPlan(
                        List.of(java, source.toString(), generated.toString()), project, Map.of(),
                        generated, Duration.ofMinutes(1)));

        IndexingArtifact artifact = executor.execute(request(project));

        Path sidecar = artifact.finalArtifact().resolveSibling("index.scip.sha256");
        assertTrue(Files.isRegularFile(sidecar), "index.scip.sha256 must sit next to index.scip");
        String content = Files.readString(sidecar);
        assertEquals(sha256(Files.readAllBytes(artifact.finalArtifact())) + "  index.scip\n", content);
        assertTrue(content.startsWith(content.substring(0, 64).toLowerCase(Locale.ROOT)));
    }

    @Test
    void artifactGeneratedDirectlyInTheRunDirectoryGetsItsDigestToo() throws Exception {
        Path project = Files.createDirectories(temporary.resolve("in-run-project"));
        Path source = writeProvider("InRunDigestProvider");
        String java = javaExecutable();
        ProcessIndexerExecutor executor = new ProcessIndexerExecutor(PROVIDER, temporary.resolve("in-run-home"),
                (request, runDirectory) -> new IndexerProcessPlan(
                        List.of(java, source.toString(), runDirectory.resolve("index.scip").toString()),
                        project, Map.of(), runDirectory.resolve("index.scip"), Duration.ofMinutes(1)));

        IndexingArtifact artifact = executor.execute(request(project));

        Path sidecar = artifact.finalArtifact().resolveSibling("index.scip.sha256");
        assertTrue(Files.isRegularFile(sidecar));
        assertEquals(sha256(Files.readAllBytes(artifact.finalArtifact())),
                Files.readString(sidecar).substring(0, 64));
    }

    @Test
    void unwritableDigestSidecarNeverFailsAPromotedExecution() throws Exception {
        // V2: the digest is a resume optimisation; a sidecar write failure after a valid promotion
        // (disk full, permission) must not turn a successful provider execution into a failure.
        Path project = Files.createDirectories(temporary.resolve("unwritable-project"));
        Path generated = project.resolve("index.scip");
        Path source = writeProvider("UnwritableSidecarProvider");
        String java = javaExecutable();
        ProcessIndexerExecutor executor = new ProcessIndexerExecutor(PROVIDER, temporary.resolve("unwritable-home"),
                (request, runDirectory) -> {
                    // Occupy the sidecar pathname with a non-empty directory: no atomic replacement can succeed.
                    Files.createDirectories(runDirectory.resolve("index.scip.sha256").resolve("blocker"));
                    return new IndexerProcessPlan(
                            List.of(java, source.toString(), generated.toString()), project, Map.of(),
                            generated, Duration.ofMinutes(1));
                });

        IndexingArtifact artifact = executor.execute(request(project));

        assertEquals("fresh-scip-bytes-for-digest", Files.readString(artifact.finalArtifact()),
                "the artifact must be promoted even though its digest could not be recorded");
        assertTrue(Files.isDirectory(artifact.finalArtifact().resolveSibling("index.scip.sha256")),
                "fixture must prove the sidecar pathname stayed unwritable");
    }

    @Test
    void failedProviderLeavesNoDigestSidecarBehind() throws Exception {
        Path project = Files.createDirectories(temporary.resolve("failing-project"));
        Path generated = project.resolve("index.scip");
        Path source = temporary.resolve("FailingProvider.java");
        Files.writeString(source, """
                import java.nio.file.*;
                public class FailingProvider {
                    public static void main(String[] args) throws Exception {
                        Files.writeString(Path.of(args[0]), "partial");
                        System.exit(3);
                    }
                }
                """);
        String java = javaExecutable();
        Path home = temporary.resolve("failing-home");
        ProcessIndexerExecutor executor = new ProcessIndexerExecutor(PROVIDER, home,
                (request, runDirectory) -> new IndexerProcessPlan(
                        List.of(java, source.toString(), generated.toString()), project, Map.of(),
                        generated, Duration.ofMinutes(1)));
        IndexingExecutionRequest request = request(project);

        try {
            executor.execute(request);
        } catch (IllegalStateException expected) {
            // provider exit code 3
        }

        Path runDirectory = home.resolve("runs").resolve(request.runId().toString()).resolve(PROVIDER);
        assertTrue(Files.isDirectory(runDirectory));
        assertFalse(Files.exists(runDirectory.resolve("index.scip.sha256")),
                "a digest must only ever describe a promoted artifact");
    }

    private Path writeProvider(String className) throws Exception {
        Path source = temporary.resolve(className + ".java");
        Files.writeString(source, """
                import java.nio.file.*;
                public class %s {
                    public static void main(String[] args) throws Exception {
                        Files.writeString(Path.of(args[0]), "fresh-scip-bytes-for-digest");
                    }
                }
                """.formatted(className));
        return source;
    }

    private static IndexingExecutionRequest request(Path project) {
        IndexerDescriptor descriptor = new IndexerDescriptor(
                PROVIDER, "1", "fake", Set.of(Language.JAVA), Set.of(), Set.of(),
                IndexerQualification.QUALIFIED, 1, List.of());
        return new IndexingExecutionRequest(UUID.randomUUID(), UUID.randomUUID(), project,
                new IndexerSelection(Language.JAVA, descriptor), IndexingMode.FULL, List.of());
    }

    private static String javaExecutable() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
