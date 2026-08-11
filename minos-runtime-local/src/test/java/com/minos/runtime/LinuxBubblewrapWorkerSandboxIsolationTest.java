package com.minos.runtime;

import com.minos.remote.DistributedIndexing.WorkerNetworkPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs(OS.LINUX)
class LinuxBubblewrapWorkerSandboxIsolationTest {

    @TempDir Path temporary;

    @Test
    void sandboxPlanNeverBindsWholeHostRoot() throws Exception {
        Path executable = Path.of("/bin/true");
        LinuxBubblewrapWorkerSandboxBackend backend = new LinuxBubblewrapWorkerSandboxBackend(executable, executable);
        List<String> command = plan(backend, executable).command();
        assertFalse(hasBind(command, "/", "/"));
        assertTrue(command.contains("--unshare-all"));
        if (Files.isSymbolicLink(Path.of("/bin"))) {
            assertTrue(hasSymlink(command, Files.readSymbolicLink(Path.of("/bin")).toString(), "/bin"));
        }
        assertTrue(backend.qualification().limitations().contains("LINUX_MINIMAL_RUNTIME_READ_ONLY_ALLOWLIST"));
    }

    @Test
    void arbitraryExecutableBindsExactFileNotItsSensitiveParent() throws Exception {
        Path executable = Path.of("/bin/true");
        Path minosHome = Files.createDirectory(temporary.resolve("home"));
        Path profile = Files.createDirectory(temporary.resolve("profile"));
        Files.writeString(profile.resolve("secret.txt"), "secret");
        Path provider = Files.writeString(profile.resolve("provider"), "provider");
        LinuxBubblewrapWorkerSandboxBackend backend =
                new LinuxBubblewrapWorkerSandboxBackend(minosHome, executable, executable);

        List<String> command = plan(backend, provider).command();
        assertTrue(hasBind(command, provider.toRealPath().toString(), provider.toAbsolutePath().normalize().toString()));
        assertFalse(hasBind(command, profile.toRealPath().toString(), profile.toRealPath().toString()));
    }

    @Test
    void managedNpmSymlinkMountsOnlyExactManagedProviderVersionRoot() throws Exception {
        Path executable = Path.of("/bin/true");
        Path minosHome = Files.createDirectory(temporary.resolve("minos-home"));
        Path providerRoot = minosHome.resolve("tools/scip-typescript");
        Path versionRoot = providerRoot.resolve("0.4.0");
        Path siblingVersion = providerRoot.resolve("9.9.9");
        Path bin = versionRoot.resolve("node_modules/.bin");
        Path target = versionRoot.resolve("node_modules/@sourcegraph/scip-typescript/dist/cli.mjs");
        Files.createDirectories(bin);
        Files.createDirectories(target.getParent());
        Files.createDirectories(siblingVersion);
        Files.writeString(target, "cli");
        Files.writeString(siblingVersion.resolve("secret-marker"), "must-not-be-visible");
        Path shim = bin.resolve("scip-typescript");
        Files.createSymbolicLink(shim, bin.relativize(target));
        LinuxBubblewrapWorkerSandboxBackend backend =
                new LinuxBubblewrapWorkerSandboxBackend(minosHome, executable, executable);

        List<String> command = plan(backend, shim).command();
        assertTrue(hasBind(command, versionRoot.toRealPath().toString(), versionRoot.toRealPath().toString()));
        assertFalse(hasBind(command, providerRoot.toRealPath().toString(), providerRoot.toRealPath().toString()));
        assertFalse(hasBind(command, minosHome.toRealPath().toString(), minosHome.toRealPath().toString()));
    }

    private IndexerProcessPlan plan(LinuxBubblewrapWorkerSandboxBackend backend, Path provider) throws Exception {
        Path working = Files.createDirectories(temporary.resolve("workspace-" + Math.abs(provider.hashCode())));
        Path run = Files.createDirectories(temporary.resolve("run-" + Math.abs(provider.hashCode())));
        Path artifact = working.resolve("index.scip");
        IndexerProcessPlan raw = new IndexerProcessPlan(
                List.of(provider.toAbsolutePath().normalize().toString()),
                working, Map.of(), artifact, Duration.ofSeconds(10));
        return backend.sandboxPlan(raw, run, WorkerNetworkPolicy.DENY);
    }

    private static boolean hasBind(List<String> command, String source, String destination) {
        for (int index = 0; index + 2 < command.size(); index++) {
            if ("--ro-bind".equals(command.get(index))
                    && source.equals(command.get(index + 1))
                    && destination.equals(command.get(index + 2))) return true;
        }
        return false;
    }

    private static boolean hasSymlink(List<String> command, String source, String destination) {
        for (int index = 0; index + 2 < command.size(); index++) {
            if ("--symlink".equals(command.get(index))
                    && source.equals(command.get(index + 1))
                    && destination.equals(command.get(index + 2))) return true;
        }
        return false;
    }
}
