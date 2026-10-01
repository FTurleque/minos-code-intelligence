package com.minos.runtime.local;

import com.minos.remote.DistributedIndexing.WorkerNetworkPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An executable script does not belong in a data directory. The sandbox launchers are artifacts of
 * the product (assembled from the jar), so they are materialised outside MINOS_HOME, read-only, and
 * checked against their SHA-256 before every use.
 */
@EnabledOnOs(OS.WINDOWS)
class WindowsLauncherScriptPlacementTest {

    @TempDir
    Path temporary;

    @Test
    void theAppContainerLauncherIsNotMaterialisedInsideMinosHome() throws Exception {
        Path home = Files.createDirectory(temporary.resolve("home"));
        WindowsAppContainerWorkerSandboxBackend backend = backend(home);
        Path launcher = launcherOf(backend.sandboxPlan(plan(), runDirectory(), WorkerNetworkPolicy.DENY));

        assertTrue(scriptsUnder(home).isEmpty(), "no executable script may sit in MINOS_HOME: " + scriptsUnder(home));
        assertFalse(launcher.startsWith(home.toAbsolutePath().normalize()));
    }

    @Test
    void theJobObjectLauncherIsNotMaterialisedInsideMinosHome() throws Exception {
        Path home = Files.createDirectory(temporary.resolve("home-job"));
        WindowsJobObjectProcessOwnership ownership = WindowsJobObjectProcessOwnership.discover(home).orElseThrow();

        IndexerProcessPlan transformed = ownership.transformer().transform(plan(), runDirectory());

        assertTrue(scriptsUnder(home).isEmpty(), "no executable script may sit in MINOS_HOME: " + scriptsUnder(home));
        assertFalse(launcherOf(transformed).startsWith(home.toAbsolutePath().normalize()));
    }

    @Test
    void theLauncherIsReadOnlyAndItsContentIsTheAssembledPackagedScript() throws Exception {
        WindowsAppContainerWorkerSandboxBackend backend = backend(Files.createDirectory(temporary.resolve("home")));
        Path launcher = launcherOf(backend.sandboxPlan(plan(), runDirectory(), WorkerNetworkPolicy.DENY));

        assertEquals(Boolean.TRUE, Files.getAttribute(launcher, "dos:readonly"));
        assertEquals(
                sha256(WindowsContainmentScript.assemble("windows-appcontainer-sandbox-v4.ps1")
                        .getBytes(StandardCharsets.UTF_8)),
                sha256(Files.readAllBytes(launcher)));
    }

    @Test
    void aLauncherThatChangedSinceItWasMaterialisedIsRefusedBeforeEveryLaunch() throws Exception {
        WindowsAppContainerWorkerSandboxBackend backend = backend(Files.createDirectory(temporary.resolve("home")));
        Path launcher = launcherOf(backend.sandboxPlan(plan(), runDirectory(), WorkerNetworkPolicy.DENY));
        Files.setAttribute(launcher, "dos:readonly", false);
        Files.writeString(launcher, "Write-Output 'substituted'", StandardCharsets.UTF_8);

        IOException refusal = assertThrows(IOException.class,
                () -> backend.sandboxPlan(plan(), runDirectory(), WorkerNetworkPolicy.DENY));

        assertTrue(refusal.getMessage().contains("integrity"), refusal.getMessage());
        assertFalse(refusal.getMessage().contains(temporary.toString()), "no absolute path in the message");
    }

    // ------------------------------------------------------------------------------ helpers

    private WindowsAppContainerWorkerSandboxBackend backend(Path home) throws IOException {
        return new WindowsAppContainerWorkerSandboxBackend(home, CommandLocator.windowsPowerShell().orElseThrow());
    }

    private IndexerProcessPlan plan() throws IOException {
        Path working = Files.createDirectories(temporary.resolve("working"));
        return new IndexerProcessPlan(
                List.of(CommandLocator.windowsPowerShell().orElseThrow().toString(), "-NoLogo"),
                working, Map.of(), runDirectory().resolve("index.scip"), Duration.ofSeconds(30));
    }

    private Path runDirectory() throws IOException {
        return Files.createDirectories(temporary.resolve("run"));
    }

    private static Path launcherOf(IndexerProcessPlan plan) {
        List<String> command = plan.command();
        return Path.of(command.get(command.indexOf("-File") + 1)).toAbsolutePath().normalize();
    }

    private static List<Path> scriptsUnder(Path home) throws IOException {
        try (Stream<Path> walk = Files.walk(home)) {
            return walk.filter(path -> path.getFileName().toString().endsWith(".ps1")).toList();
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
