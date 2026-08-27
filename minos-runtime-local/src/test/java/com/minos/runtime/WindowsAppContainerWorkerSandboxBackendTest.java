package com.minos.runtime;

import com.minos.discovery.ProjectDiscovery.BuildSystem;
import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexerCapability;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;
import com.minos.orchestration.IndexerQualification;
import com.minos.orchestration.IndexingMode;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.remote.DistributedIndexing.WorkerNetworkPolicy;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WindowsAppContainerWorkerSandboxBackendTest {

    @Test
    void qualificationOnlyPermitsSandboxClaimOnWindows() throws Exception {
        Path home = Files.createTempDirectory("minos-appcontainer-home-");
        var discovered = WindowsAppContainerWorkerSandboxBackend.discover(home);
        if (WorkerSandboxQualification.currentPlatform() == WorkerSandboxQualification.Platform.WINDOWS) {
            assumeTrue(discovered.isPresent(), "real Windows AppContainer/Job Object qualification is required");
            WorkerSandboxQualification qualification = discovered.orElseThrow().qualification();
            assertTrue(discovered.orElseThrow().enforcesNetworkDeny());
            assertTrue(qualification.containment().aggregateJobBoundaryEnforced());
            assertFalse(qualification.containment().hardFilesystemQuotaEnforced());
            assertFalse(qualification.sandboxClaimPermitted(),
                    "sampled filesystem limits must not qualify execution of untrusted code");
            assertEquals(
                    WorkerSandboxQualification.TrustDisposition.UNTRUSTED_CODE_UNSUPPORTED,
                    qualification.trustDisposition());
            assertTrue(qualification.limitations().contains("WINDOWS_RUNTIME_CAPABILITY_PROBE_REQUIRED"));
            assertTrue(qualification.limitations().contains(
                    "WINDOWS_APPCONTAINER_PRIVATE_FILE_AND_REGISTRY_STORAGE_SUPERVISED"));
            assertEquals(
                    ProviderWriteQuota.DEFAULT_MAX_BYTES,
                    WindowsAppContainerWorkerSandboxBackend.EXPLICIT_ROOT_WRITE_QUOTA.maxBytes()
                            + WindowsAppContainerWorkerSandboxBackend.PRIVATE_STORAGE_MAX_BYTES);
            assertEquals(
                    ProviderWriteQuota.DEFAULT_MAX_ENTRIES,
                    WindowsAppContainerWorkerSandboxBackend.EXPLICIT_ROOT_WRITE_QUOTA.maxEntries()
                            + WindowsAppContainerWorkerSandboxBackend.PRIVATE_STORAGE_MAX_ENTRIES);
        } else {
            assertTrue(discovered.isEmpty());
        }
    }

    @Test
    void realWindowsSandboxUsesAppContainerJobLimitsAndBlocksNetworkHostWriteAndPrivateRegistryWrite()
            throws Exception {
        if (WorkerSandboxQualification.currentPlatform() != WorkerSandboxQualification.Platform.WINDOWS) return;

        Path home = Files.createTempDirectory("minos-appcontainer-home-");
        var discovered = WindowsAppContainerWorkerSandboxBackend.discover(home);
        assumeTrue(discovered.isPresent(), "qualified Windows AppContainer backend is required");
        WindowsAppContainerWorkerSandboxBackend backend = discovered.orElseThrow();
        Path childPowerShell = CommandLocator.windowsPowerShell()
                .orElseThrow(() -> new AssertionError("PowerShell child executable is unavailable"));
        Path working = Files.createTempDirectory("minos-appcontainer-working-");
        Path run = Files.createTempDirectory("minos-appcontainer-run-");
        Path artifact = run.resolve("index.scip");
        Path hostEscape = Path.of(
                System.getProperty("user.home"),
                "minos-appcontainer-escape-" + UUID.randomUUID() + ".txt").toAbsolutePath().normalize();
        Files.deleteIfExists(hostEscape);

        Path childScript = working.resolve("sandbox-child.ps1");
        Files.writeString(childScript, """
                param([string] $HostEscape, [string] $Artifact)
                $ErrorActionPreference = 'Stop'
                $client = $null
                $iar = $null
                try {
                  $client = [System.Net.Sockets.TcpClient]::new()
                  $iar = $client.BeginConnect('1.1.1.1', 53, $null, $null)
                  if ($iar.AsyncWaitHandle.WaitOne(1500) -and $client.Connected) { exit 41 }
                } catch { }
                finally {
                  if ($iar -and $iar.AsyncWaitHandle) { $iar.AsyncWaitHandle.Dispose() }
                  if ($client) { $client.Dispose() }
                }
                try {
                  [System.IO.File]::WriteAllText($HostEscape, 'escape')
                  exit 42
                } catch { }
                try {
                  New-Item -Path 'HKCU:\\Software\\MinosAppContainerWriteProbe' -Force | Out-Null
                  New-ItemProperty -Path 'HKCU:\\Software\\MinosAppContainerWriteProbe' -Name value -Value escape -Force | Out-Null
                  exit 43
                } catch { }
                [System.IO.File]::WriteAllText($Artifact, 'qualified-appcontainer-artifact')
                exit 0
                """, StandardCharsets.US_ASCII);

        IndexerProcessPlan original = new IndexerProcessPlan(
                List.of(
                        childPowerShell.toString(),
                        "-NoLogo",
                        "-NoProfile",
                        "-NonInteractive",
                        "-ExecutionPolicy",
                        "Bypass",
                        "-File",
                        childScript.toString(),
                        hostEscape.toString(),
                        artifact.toString()),
                working,
                Map.of(),
                artifact,
                Duration.ofSeconds(30));

        IndexerProcessPlan sandboxed = backend.sandboxPlan(original, run);
        String planText = Files.readString(run.resolve("windows-appcontainer-plan.txt"), StandardCharsets.UTF_8);
        assertTrue(planText.contains("networkPolicy=DENY"));
        assertTrue(planText.contains("privateStorageMaxBytes="
                + WindowsAppContainerWorkerSandboxBackend.PRIVATE_STORAGE_MAX_BYTES));
        assertTrue(planText.contains("privateStorageMaxEntries="
                + WindowsAppContainerWorkerSandboxBackend.PRIVATE_STORAGE_MAX_ENTRIES));
        Process process = new ProcessBuilder(sandboxed.command())
                .directory(working.toFile())
                .redirectErrorStream(true)
                .start();
        boolean completed = process.waitFor(30, TimeUnit.SECONDS);
        if (!completed) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(completed, () -> "AppContainer qualification process timed out; output=" + output);
        assertEquals(0, process.exitValue(), output);
        assertFalse(Files.exists(hostEscape), "AppContainer child must not write outside granted roots");
        assertEquals("qualified-appcontainer-artifact", Files.readString(artifact, StandardCharsets.UTF_8));
    }

    @Test
    void allowPolicyKeepsAppContainerAndGrantsOnlyInternetClientCapability() throws Exception {
        if (WorkerSandboxQualification.currentPlatform() != WorkerSandboxQualification.Platform.WINDOWS) return;
        Path home = Files.createTempDirectory("minos-appcontainer-allow-home-");
        var discovered = WindowsAppContainerWorkerSandboxBackend.discover(home);
        assumeTrue(discovered.isPresent(), "qualified Windows AppContainer backend is required");
        WindowsAppContainerWorkerSandboxBackend backend = discovered.orElseThrow();
        Path childPowerShell = CommandLocator.windowsPowerShell().orElseThrow();
        Path working = Files.createTempDirectory("minos-appcontainer-allow-working-");
        Path run = Files.createTempDirectory("minos-appcontainer-allow-run-");
        Path artifact = run.resolve("index.scip");
        IndexerProcessPlan original = new IndexerProcessPlan(
                List.of(childPowerShell.toString(), "-NoLogo"),
                working,
                Map.of(),
                artifact,
                Duration.ofSeconds(30));

        IndexerProcessPlan sandboxed = backend.sandboxPlan(original, run, WorkerNetworkPolicy.ALLOW);

        assertTrue(sandboxed.command().contains("-File"));
        assertTrue(Files.readString(run.resolve("windows-appcontainer-plan.txt"), StandardCharsets.UTF_8)
                .contains("networkPolicy=ALLOW"));
        String launcher = Files.readString(
                home.resolve("sandbox/windows-appcontainer-sandbox-v4.ps1"), StandardCharsets.UTF_8);
        assertTrue(launcher.contains("S-1-15-3-1"));
        assertTrue(launcher.contains("GetAppContainerFolderPath"));
        assertTrue(launcher.contains("DenyPrivateRegistryWrites"));
        assertTrue(launcher.contains("MINOS_APPCONTAINER_PRIVATE_STORAGE_QUOTA_BREACH"));
    }

    @Test
    void qualifiedBackendLaunchesRealProcessIndexerExecutor() throws Exception {
        if (WorkerSandboxQualification.currentPlatform() != WorkerSandboxQualification.Platform.WINDOWS) return;
        Path home = Files.createTempDirectory("minos-windows-process-home-");
        var discovered = WindowsAppContainerWorkerSandboxBackend.discover(home);
        assumeTrue(discovered.isPresent(), "qualified Windows sandbox backend is required for process-path qualification");
        WindowsAppContainerWorkerSandboxBackend backend = discovered.orElseThrow();
        Path project = Files.createTempDirectory("minos-windows-process-project-");
        Path childPowerShell = CommandLocator.windowsPowerShell()
                .orElseThrow(() -> new AssertionError("PowerShell child executable is unavailable"));
        Path providerScript = project.resolve("provider-child.ps1");
        Files.writeString(providerScript, """
                param([string] $Artifact)
                if ($env:MINOS_TEST_PROVIDER_EXPLICIT -ne 'allowed') { exit 51 }
                if (-not [string]::IsNullOrEmpty($env:MAVEN_ARGS)) { exit 52 }
                [System.IO.File]::WriteAllText($Artifact, 'process-sandbox-artifact')
                exit 0
                """, StandardCharsets.US_ASCII);
        IndexingExecutionRequest request = executionRequest(project);
        ProcessIndexerExecutor executor = new ProcessIndexerExecutor(
                "fixture-provider",
                home,
                (ignored, runDirectory) -> {
                    Path generated = runDirectory.resolve("provider-generated.scip");
                    return new IndexerProcessPlan(
                            List.of(
                                    childPowerShell.toString(),
                                    "-NoLogo",
                                    "-NoProfile",
                                    "-NonInteractive",
                                    "-ExecutionPolicy",
                                    "Bypass",
                                    "-File",
                                    providerScript.toString(),
                                    generated.toString()),
                            project,
                            Map.of("MINOS_TEST_PROVIDER_EXPLICIT", "allowed"),
                            generated,
                            Duration.ofSeconds(20));
                });

        IndexingArtifact artifact = backend.execute(executor, request, WorkerNetworkPolicy.ALLOW);

        assertEquals("fixture-provider", artifact.indexerId());
        assertTrue(Files.isRegularFile(artifact.finalArtifact()));
        assertEquals("process-sandbox-artifact", Files.readString(artifact.finalArtifact(), StandardCharsets.UTF_8));
    }

    @Test
    void toolchainHomeEnvironmentGrantsItsRootButNeverAProfileWideLocation() throws Exception {
        if (WorkerSandboxQualification.currentPlatform() != WorkerSandboxQualification.Platform.WINDOWS) return;
        Path home = Files.createTempDirectory("minos-appcontainer-toolchain-home-");
        var discovered = WindowsAppContainerWorkerSandboxBackend.discover(home);
        assumeTrue(discovered.isPresent(), "qualified Windows AppContainer backend is required");
        WindowsAppContainerWorkerSandboxBackend backend = discovered.orElseThrow();
        Path childPowerShell = CommandLocator.windowsPowerShell().orElseThrow();
        Path projectJdk = Files.createDirectories(
                Files.createTempDirectory("minos-appcontainer-project-jdk-").resolve("bin").getParent());
        Path userProfile = Path.of(System.getenv("USERPROFILE")).toAbsolutePath().normalize();

        // A project JDK outside MINOS' own runtime must become readable, or scip-java cannot even
        // probe its own java.exe; the user profile must stay unreadable whatever the variable says.
        Set<Path> granted = readRootsOf(backend, childPowerShell,
                Map.of("JAVA_HOME", projectJdk.toString()));
        Set<Path> refused = readRootsOf(backend, childPowerShell,
                Map.of("JAVA_HOME", userProfile.toString()));

        assertTrue(granted.contains(projectJdk.toRealPath()),
                () -> "project toolchain root must be granted, got " + granted);
        assertFalse(refused.contains(userProfile),
                () -> "a profile-wide toolchain value must never be granted, got " + refused);
    }

    @Test
    void hostJvmJavaHomeIsNeverGrantedAsAProviderReadRoot() throws Exception {
        if (WorkerSandboxQualification.currentPlatform() != WorkerSandboxQualification.Platform.WINDOWS) return;
        Path home = Files.createTempDirectory("minos-appcontainer-javahome-host-");
        var discovered = WindowsAppContainerWorkerSandboxBackend.discover(home);
        assumeTrue(discovered.isPresent(), "qualified Windows AppContainer backend is required");
        WindowsAppContainerWorkerSandboxBackend backend = discovered.orElseThrow();
        Path childPowerShell = CommandLocator.windowsPowerShell().orElseThrow();

        // Reproduces MINOS running under a JDK an IDE run configuration selected (e.g. from Program
        // Files, as IntelliJ does): indexing must never need to read, let alone ACL, that JDK just
        // because it happens to be the JVM currently executing MINOS.
        Path simulatedDevJdk = Files.createTempDirectory("minos-appcontainer-simulated-dev-jdk-");
        String originalJavaHome = System.getProperty("java.home");
        System.setProperty("java.home", simulatedDevJdk.toString());
        try {
            Set<Path> granted = readRootsOf(backend, childPowerShell, Map.of());
            assertFalse(granted.contains(simulatedDevJdk.toRealPath()),
                    () -> "the host JVM's java.home must never be a provider read root, got " + granted);
        } finally {
            System.setProperty("java.home", originalJavaHome);
        }
    }

    @Test
    void ungrantableReadRootFailsClosedInsteadOfReachingIcacls() {
        if (WorkerSandboxQualification.currentPlatform() != WorkerSandboxQualification.Platform.WINDOWS) return;
        // %SystemRoot% itself (not a MINOS-owned path) is used directly to exercise the ACL
        // grantability primitive in isolation; addReadRoot never reaches it for a real plan because
        // isWindowsSystemRoot already excludes it earlier.
        Path systemRoot = Path.of(System.getenv("SystemRoot")).toAbsolutePath().normalize();
        boolean grantable = WindowsAppContainerWorkerSandboxBackend.isAclGrantable(systemRoot);
        assumeTrue(!grantable, "this account unexpectedly holds WRITE_DAC on %SystemRoot%; "
                + "not representative of a non-elevated standard user");
        assertThrows(IllegalStateException.class,
                () -> WindowsAppContainerWorkerSandboxBackend.requireAclGrantable(systemRoot));
    }

    @Test
    void aclGrantabilityProbeSurvivesConcurrentGrantRevokeWithoutWipingTheAcl() throws Exception {
        if (WorkerSandboxQualification.currentPlatform() != WorkerSandboxQualification.Platform.WINDOWS) return;
        Path target = Files.createTempFile("minos-acl-race-", ".ps1");
        String currentUser = System.getProperty("user.name");
        // A distinctive marker ACE that must survive the stress below: proves the grantability probe
        // never wipes an ACE it did not itself add, even raced against another process's own
        // concurrent, unrelated grant/remove on the very same file -- exactly the interleaving that
        // used to leave a shared sandbox resource (the AppContainer launcher script) with an empty
        // DACL, unreadable by anyone including its owner, once a whole-ACL-list read/write-back probe
        // captured and persisted a transient state from mid-flight through someone else's own
        // icacls call.
        assertEquals(0, runIcacls(target, "/grant", currentUser + ":(W)"), "marker grant must succeed");

        java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicReference<Exception> racerFailure = new java.util.concurrent.atomic.AtomicReference<>();
        Thread racer = new Thread(() -> {
            while (!stop.get()) {
                try {
                    runIcacls(target, "/grant", "*S-1-1-0:(R)");
                    runIcacls(target, "/remove:g", "*S-1-1-0");
                } catch (Exception failure) {
                    racerFailure.set(failure);
                    return;
                }
            }
        });
        racer.start();
        try {
            for (int attempt = 0; attempt < 40; attempt++) {
                assertTrue(WindowsAppContainerWorkerSandboxBackend.isAclGrantable(target),
                        "own temp file must remain ACL-grantable throughout");
            }
        } finally {
            stop.set(true);
            racer.join(10_000);
        }
        assertEquals(null, racerFailure.get(), () -> "racer thread failed: " + racerFailure.get());

        String finalAcl = icaclsOutput(target);
        assertTrue(finalAcl.contains(currentUser),
                () -> "the pre-existing marker ACE must survive concurrent probing, got:\n" + finalAcl);
    }

    private static int runIcacls(Path target, String... arguments) throws Exception {
        List<String> command = new java.util.ArrayList<>();
        command.add(Path.of(System.getenv("SystemRoot"), "System32", "icacls.exe").toString());
        command.add(target.toString());
        command.addAll(List.of(arguments));
        command.add("/q");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        return process.waitFor();
    }

    private static String icaclsOutput(Path target) throws Exception {
        Process process = new ProcessBuilder(
                Path.of(System.getenv("SystemRoot"), "System32", "icacls.exe").toString(),
                target.toString())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        process.waitFor();
        return output;
    }

    /** Decodes the read roots the sandbox plan really grants for the given provider environment. */
    private static Set<Path> readRootsOf(
            WindowsAppContainerWorkerSandboxBackend backend,
            Path childExecutable,
            Map<String, String> environment
    ) throws Exception {
        Path working = Files.createTempDirectory("minos-appcontainer-toolchain-working-");
        Path run = Files.createTempDirectory("minos-appcontainer-toolchain-run-");
        IndexerProcessPlan original = new IndexerProcessPlan(
                List.of(childExecutable.toString(), "-NoLogo"),
                working,
                environment,
                run.resolve("index.scip"),
                Duration.ofSeconds(30));
        backend.sandboxPlan(original, run);
        Map<String, String> plan = new java.util.LinkedHashMap<>();
        for (String line : Files.readAllLines(run.resolve("windows-appcontainer-plan.txt"), StandardCharsets.UTF_8)) {
            int separator = line.indexOf('=');
            if (separator > 0) plan.put(line.substring(0, separator), line.substring(separator + 1));
        }
        Set<Path> roots = new java.util.LinkedHashSet<>();
        int count = Integer.parseInt(plan.getOrDefault("read.count", "0"));
        for (int index = 0; index < count; index++) {
            byte[] decoded = java.util.Base64.getDecoder().decode(plan.get("read." + index));
            roots.add(Path.of(new String(decoded, StandardCharsets.UTF_8)).toAbsolutePath().normalize());
        }
        return roots;
    }

    private static IndexingExecutionRequest executionRequest(Path projectRoot) {
        IndexerDescriptor descriptor = new IndexerDescriptor(
                "fixture-provider",
                "1.0.0",
                "Fixture provider",
                Set.of(Language.JAVA),
                Set.of(BuildSystem.MAVEN),
                Set.of(IndexerCapability.SYMBOLS),
                IndexerQualification.QUALIFIED,
                1,
                List.of());
        return new IndexingExecutionRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                projectRoot,
                new IndexerSelection(Language.JAVA, descriptor),
                IndexingMode.FULL,
                List.of());
    }
}
