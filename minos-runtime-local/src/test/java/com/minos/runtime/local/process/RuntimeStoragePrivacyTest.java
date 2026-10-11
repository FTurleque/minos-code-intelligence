package com.minos.runtime.local.process;

import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.io.PrivateLocalStorage;
import com.minos.io.PrivateLocalStorage.Privacy;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;
import com.minos.orchestration.IndexerQualification;
import com.minos.orchestration.IndexingMode;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S5: everything MINOS keeps for a run or a cached bundle is owner-only. These directories and files
 * hold a copy of, or an index derived from, the user's code: Java defaults would leave them readable
 * by other local users. This class covers the diagnostic outputs and the run directory of a provider process.
 */
class RuntimeStoragePrivacyTest {

    private static void assertPrivate(Path path) throws IOException {
        assertEquals(Privacy.ENFORCED, PrivateLocalStorage.privacyOf(path), "not owner-only: " + path.getFileName());
    }

    @Test
    void aDiagnosticOutputFileAndItsDirectoryAreOwnerOnly(@TempDir Path temp) throws Exception {
        Path target = temp.resolve("runs").resolve("provider.stdout.log");

        try (OutputStream output = BoundedProcessOutput.openTarget(target)) {
            output.write('x');
        }

        assertPrivate(target);
        assertPrivate(target.getParent());
    }

    @Test
    void aDiagnosticOutputNeverFollowsALinkPlacedAtItsName(@TempDir Path temp) throws Exception {
        Path outside = Files.writeString(temp.resolve("outside.txt"), "untouched", StandardCharsets.UTF_8);
        Path target = temp.resolve("provider.stderr.log");
        Files.createSymbolicLink(target, outside);

        try (OutputStream output = BoundedProcessOutput.openTarget(target)) {
            output.write('x');
        }

        assertEquals("untouched", Files.readString(outside, StandardCharsets.UTF_8));
        assertTrue(Files.isRegularFile(target, java.nio.file.LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void theRunDirectoryItsLogsAndTheDigestSidecarAreOwnerOnly(@TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path generated = project.resolve("index.scip");
        Path source = temp.resolve("Provider.java");
        Files.writeString(source, """
                import java.nio.file.*;
                public class Provider {
                    public static void main(String[] args) throws Exception {
                        Files.writeString(Path.of(args[0]), "fresh-scip");
                    }
                }
                """);
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java").toString();
        ProcessIndexerExecutor executor = new ProcessIndexerExecutor(
                "fake-provider",
                temp.resolve("home"),
                (request, runDirectory) -> new IndexerProcessPlan(
                        List.of(java, source.toString(), generated.toString()),
                        project,
                        Map.of(),
                        generated,
                        Duration.ofMinutes(1)));
        IndexerDescriptor descriptor = new IndexerDescriptor(
                "fake-provider", "1", "fake", Set.of(Language.JAVA), Set.of(), Set.of(),
                IndexerQualification.QUALIFIED, 1, List.of());

        var artifact = executor.execute(new IndexingExecutionRequest(
                UUID.randomUUID(), UUID.randomUUID(), project,
                new IndexerSelection(Language.JAVA, descriptor), IndexingMode.FULL, List.of()));

        Path runDirectory = artifact.finalArtifact().getParent();
        assertPrivate(runDirectory);
        assertPrivate(runDirectory.resolve("provider.stdout.log"));
        assertPrivate(runDirectory.resolve("provider.stderr.log"));
        Path sidecar = ProcessIndexerExecutor.artifactDigestSidecar(artifact.finalArtifact());
        assertTrue(Files.isRegularFile(sidecar), "the digest sidecar was not recorded");
        assertPrivate(sidecar);
    }
}
