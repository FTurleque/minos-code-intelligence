package com.minos.runtime.local.wsl;

import com.minos.discovery.ProjectDiscovery.BuildSystem;
import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.incremental.ProjectFingerprintService;
import com.minos.io.InternalFailureJournal;
import com.minos.orchestration.IndexerCapability;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;
import com.minos.orchestration.IndexerQualification;
import com.minos.orchestration.IndexingMode;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.runtime.local.distributed.DistributedArtifactBundleStore;
import com.minos.testsupport.LogCapture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The installation reads the worker's diagnostic only when it follows the closed grammar. */
class WslIndexWorkerDiagnosticTest {

    private static final String PROVIDER = "scip-java";
    private static final String VERSION = "0.13.2";
    private static final String DISTRIBUTION = "Ubuntu-24.04";
    private static final String CONFORMING = WslIndexWorker.STATUS_PREFIX
            + "DIAGNOSTIC ref=0123abcd types=java.lang.NullPointerException at=com.minos.demo.Widget.render:42"
            + " frames=com.minos.demo.Widget.render:42";
    private static final String FAILED = WslIndexWorker.STATUS_PREFIX + "FAILED scip-java: NullPointerException"
            + " (ref 0123abcd)";

    @TempDir Path temp;
    private Path home;
    private Path project;
    private Path wsl;
    private Path jar;

    @BeforeEach
    void layout() throws IOException {
        home = Files.createDirectories(temp.resolve("home"));
        project = Files.createDirectories(temp.resolve("project"));
        wsl = Files.writeString(temp.resolve("wsl.exe"), "");
        jar = Files.writeString(temp.resolve("minos.jar"), "");
    }

    @Test
    void aConformingDiagnosticIsLoggedJournaledAndItsReferenceReachesThePublicFailure() throws Exception {
        try (LogCapture log = new LogCapture(WslIndexWorker.class)) {
            WslIndexerExecutor worker = executor(output(CONFORMING, FAILED), true);
            IndexingExecutionRequest request = request();
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> worker.execute(request));

            assertTrue(failure.getMessage().startsWith("the WSL worker failed in distribution " + DISTRIBUTION),
                    failure.getMessage());
            assertTrue(failure.getMessage().contains("0123abcd"), failure.getMessage());
            assertEquals(1, log.warnings().size(), log.warnings().toString());
            String line = log.warnings().get(0);
            assertTrue(line.contains("distribution=" + DISTRIBUTION), line);
            assertTrue(line.contains("types=java.lang.NullPointerException"), line);
            assertTrue(line.contains("at=com.minos.demo.Widget.render:42"), line);
            assertTrue(line.contains("ref=0123abcd"), line);
        }
        List<Path> entries = entries();
        assertEquals(1, entries.size());
        assertTrue(entries.get(0).getFileName().toString().endsWith("-0123abcd.txt"));
        String entry = Files.readString(entries.get(0));
        assertTrue(entry.contains("surface=wsl-worker"), entry);
        assertTrue(entry.contains("java.lang.NullPointerException"), entry);
        assertFalse(entry.contains(temp.toString()), "no absolute path in the entry");
    }

    @Test
    void theReferenceIsAddedToAPublicFailureWhoseStatusDetailIsWithheld() throws IOException {
        String sensitive = WslIndexWorker.STATUS_PREFIX + "FAILED build in /home/user/project";
        WslIndexerExecutor worker = executor(output(CONFORMING, sensitive), false);
        IndexingExecutionRequest request = request();

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> worker.execute(request));

        assertTrue(failure.getMessage().endsWith("(ref 0123abcd)"), failure.getMessage());
        assertFalse(failure.getMessage().contains("/home/"), failure.getMessage());
    }

    @Test
    void aDiagnosticWithAPathSeparatorOrTooLongIsIgnoredAndNothingOfItIsLoggedOrRendered() throws Exception {
        String withPath = CONFORMING.replace("at=com.minos.demo.Widget.render:42", "at=/mnt/c/Users/alice/x");
        String withBackslash = CONFORMING.replace("frames=com.minos.demo.Widget.render:42", "frames=C:\\Users\\alice");
        String tooLong = CONFORMING + "a".repeat(WslIndexWorkerDiagnosticTest.maxPayload());

        for (String bad : List.of(withPath, withBackslash, tooLong)) {
            try (LogCapture log = new LogCapture(WslIndexWorker.class)) {
                WslIndexerExecutor worker = executor(output(bad, FAILED), true);
                IndexingExecutionRequest request = request();
                IllegalStateException failure = assertThrows(IllegalStateException.class,
                        () -> worker.execute(request));

                assertEquals(List.of(), log.warnings());
                assertFalse(failure.getMessage().contains("alice"), failure.getMessage());
                assertTrue(failure.getMessage().contains("NullPointerException (ref 0123abcd)"),
                        "the public failure is the one of the status: " + failure.getMessage());
            }
        }
        assertEquals(List.of(), entries());
    }

    @Test
    void theStatusLineIsAlwaysTheStatusNeverTheDiagnostic() {
        byte[] diagnosticFirst = output(CONFORMING, FAILED);
        byte[] diagnosticLast = output(FAILED, CONFORMING);

        assertEquals(Optional.of("FAILED scip-java: NullPointerException (ref 0123abcd)"),
                WslIndexWorker.statusLine(diagnosticFirst));
        assertEquals(Optional.of("FAILED scip-java: NullPointerException (ref 0123abcd)"),
                WslIndexWorker.statusLine(diagnosticLast));
        assertEquals("0123abcd", WslIndexWorker.diagnosticLine(diagnosticFirst).orElseThrow().reference());
    }

    private static int maxPayload() {
        return com.minos.diagnostics.WorkerDiagnostic.MAX_PAYLOAD_CHARS;
    }

    private static byte[] output(String first, String second) {
        return ("starting\n" + first + "\n" + second + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private WslIndexerExecutor executor(byte[] stdout, boolean journalEnabled) throws IOException {
        Map<String, String> environment = journalEnabled
                ? Map.of(InternalFailureJournal.ENVIRONMENT_VARIABLE, InternalFailureJournal.ENABLED) : Map.of();
        WslIndexWorker worker = new WslIndexWorker(
                new WslIndexWorker.Launch(wsl, DISTRIBUTION, jar, "com.minos.cli.MinosLauncher", Duration.ofMinutes(5)),
                (command, timeout) -> new WslCommandResult(1, stdout), home, path -> "/mnt/c/mapped",
                new InternalFailureJournal(home, environment, new Properties()));
        return new WslIndexerExecutor(PROVIDER, VERSION, worker, new DistributedArtifactBundleStore(home),
                new ProjectFingerprintService(), home);
    }

    private IndexingExecutionRequest request() {
        IndexerDescriptor descriptor = new IndexerDescriptor(PROVIDER, VERSION, PROVIDER, Set.of(Language.JAVA),
                Set.of(BuildSystem.MAVEN), Set.of(IndexerCapability.SYMBOLS), IndexerQualification.QUALIFIED, 1,
                List.of());
        return new IndexingExecutionRequest(UUID.randomUUID(), UUID.randomUUID(), project, project, Path.of(""),
                new IndexerSelection(Language.JAVA, descriptor), IndexingMode.FULL, List.of(), Optional.empty());
    }

    private List<Path> entries() throws IOException {
        Path directory = home.resolve("logs").resolve("internal-failures");
        if (!Files.isDirectory(directory)) return List.of();
        try (Stream<Path> stream = Files.list(directory)) {
            return stream.toList();
        }
    }
}
