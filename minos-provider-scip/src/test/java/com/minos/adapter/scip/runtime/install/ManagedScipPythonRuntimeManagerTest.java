package com.minos.adapter.scip.runtime.install;

import com.minos.runtime.ProviderRuntimeStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The managed scip-python runtime: what it reports before anything is installed, which prerequisite it refuses
 * to continue without, and what it leaves on disk when the npm step fails. The tools it needs are looked up in
 * {@code PATH}, so the scenarios that depend on them run in a child JVM whose {@code PATH} is a directory of
 * fake tools written by the test.
 */
class ManagedScipPythonRuntimeManagerTest {

    private static final String PROVIDER = ManagedScipPythonRuntimeManager.PROVIDER_ID;
    private static final String VERSION = ManagedScipPythonRuntimeManager.VERSION;

    @TempDir
    Path home;

    @TempDir
    Path tools;

    @TempDir
    Path scratch;

    private Path installedExecutable() {
        return home.resolve("tools").resolve(PROVIDER).resolve(VERSION).resolve("node_modules").resolve(".bin")
                .resolve(FakeTools.windows() ? "scip-python.cmd" : "scip-python");
    }

    private ChildJvm.Outcome child(String actions) throws IOException, InterruptedException {
        return ChildJvm.run(tools, scratch, Map.of("MINOS_TEAM_TOKEN", "do-not-leak"), "python", PROVIDER,
                home.toString(), actions);
    }

    /** A fake npm that records how it was called and builds the tree a real {@code npm ci} would. */
    private void fakeNpm(boolean createEntryPoint) throws IOException {
        List<String> windows = new java.util.ArrayList<>(List.of(
                "echo %* > \"" + tools.resolve("npm-args.txt") + "\"",
                "echo token=[%MINOS_TEAM_TOKEN%]> \"" + tools.resolve("npm-env.txt") + "\"",
                "mkdir \"%~3\\node_modules\\.bin\"",
                "echo.> \"%~3\\node_modules\\.bin\\scip-python.cmd\""));
        if (createEntryPoint) {
            windows.add("mkdir \"%~3\\node_modules\\@sourcegraph\\scip-python\"");
            windows.add("echo.> \"%~3\\node_modules\\@sourcegraph\\scip-python\\index.js\"");
        }
        windows.add("exit /b 0");
        FakeTools.write(tools, "npm", windows, List.of(
                "echo \"$@\" > '" + tools.resolve("npm-args.txt") + "'",
                "echo \"token=[$MINOS_TEAM_TOKEN]\" > '" + tools.resolve("npm-env.txt") + "'",
                "/bin/mkdir -p \"$3/node_modules/.bin\"",
                ": > \"$3/node_modules/.bin/scip-python\"",
                "exit 0"));
    }

    private void everyPrerequisite() throws IOException {
        FakeTools.succeeding(tools, "node");
        FakeTools.succeeding(tools, "python");
        FakeTools.succeeding(tools, "pip3");
    }

    // ------------------------------------------------------------------ in-process: reporting and refusals

    @Test
    void listReportsTheSingleManagedProviderAsNotRequiredByDefault() {
        List<ProviderRuntimeStatus> statuses = new ManagedScipPythonRuntimeManager(home).list();

        assertEquals(1, statuses.size());
        assertEquals(PROVIDER, statuses.get(0).providerId());
        assertEquals(VERSION, statuses.get(0).version());
        assertFalse(statuses.get(0).requiredByDefault());
    }

    @Test
    void anEmptyHomeIsNotInstalledAndInspectionCreatesNothing() {
        ProviderRuntimeStatus status = new ManagedScipPythonRuntimeManager(home).inspect(PROVIDER);

        assertEquals(ProviderRuntimeStatus.State.NOT_INSTALLED, status.state());
        assertTrue(status.executable().isEmpty());
        assertTrue(status.diagnostics().contains("managed scip-python " + VERSION + " is not installed"),
                status.diagnostics().toString());
        assertTrue(status.diagnostics().stream().noneMatch(line -> line.contains(home.toString())),
                "a diagnostic must not carry the absolute home path: " + status.diagnostics());
        assertFalse(Files.exists(home.resolve("tools")), "readiness inspection must not mutate the home");
    }

    @Test
    void anUnknownProviderIsRefusedByEveryEntryPoint() {
        ManagedScipPythonRuntimeManager manager = new ManagedScipPythonRuntimeManager(home);

        assertThrows(IllegalArgumentException.class, () -> manager.inspect("scip-typescript"));
        assertThrows(IllegalArgumentException.class, () -> manager.install("scip-typescript"));
        assertThrows(IllegalArgumentException.class, () -> manager.executor("scip-typescript"));
        assertFalse(Files.exists(home.resolve("tools")));
    }

    @Test
    void theManagerRequiresAHomeDirectory() {
        assertThrows(NullPointerException.class, () -> new ManagedScipPythonRuntimeManager(null));
    }

    @Test
    void anExecutorIsNeverHandedOutWhileTheRuntimeIsNotInstalled() {
        ManagedScipPythonRuntimeManager manager = new ManagedScipPythonRuntimeManager(home);

        IllegalStateException refusal = assertThrows(IllegalStateException.class, () -> manager.executor(PROVIDER));

        assertTrue(refusal.getMessage().contains("provider runtime is not ready: " + PROVIDER), refusal.getMessage());
    }

    // ------------------------------------------------------------------ child JVM: prerequisites on PATH

    @Test
    void withoutAnyPrerequisiteTheRuntimeIsNotInstalledAndInstallationIsRefusedBeforeTouchingTheHome() throws Exception {
        ChildJvm.Outcome outcome = child("inspect,install,executor");

        assertEquals(0, outcome.exitCode(), outcome.stderr());
        assertEquals("NOT_INSTALLED", outcome.get("inspect.state"));
        String diagnostics = outcome.get("inspect.diagnostics");
        assertTrue(diagnostics.contains(ExternalPrerequisite.of("Node.js 16+ is required by scip-python")), diagnostics);
        assertTrue(diagnostics.contains(ExternalPrerequisite.of("npm is required to install scip-python")), diagnostics);
        assertTrue(diagnostics.contains(ExternalPrerequisite.of("Python 3.10+ is required in PATH by scip-python")),
                diagnostics);
        assertEquals("IllegalStateException: npm is required to install scip-python", outcome.get("install.failure"));
        assertTrue(outcome.get("executor.failure").contains("provider runtime is not ready"), outcome.get("executor.failure"));
        assertFalse(Files.exists(home.resolve("tools")), "a refused installation must not create anything");
    }

    @Test
    void installationNeedsNodeAfterNpm() throws Exception {
        FakeTools.succeeding(tools, "npm");

        ChildJvm.Outcome outcome = child("install");

        assertEquals("IllegalStateException: Node.js 16+ is required to run scip-python", outcome.get("install.failure"));
        assertFalse(Files.exists(home.resolve("tools")));
    }

    @Test
    void installationNeedsPythonAfterNode() throws Exception {
        FakeTools.succeeding(tools, "npm");
        FakeTools.succeeding(tools, "node");

        ChildJvm.Outcome outcome = child("install");

        assertEquals("IllegalStateException: Python 3.10+ is required in PATH to run scip-python",
                outcome.get("install.failure"));
        assertFalse(Files.exists(home.resolve("tools")));
    }

    @Test
    void installationNeedsPipBesideThePythonItSelects() throws Exception {
        FakeTools.succeeding(tools, "npm");
        FakeTools.succeeding(tools, "node");
        FakeTools.succeeding(tools, "python3");

        ChildJvm.Outcome outcome = child("inspect,install");

        assertEquals("IllegalStateException: pip is required by scip-python and must be available with the selected "
                + "Python runtime", outcome.get("install.failure"));
        assertTrue(outcome.get("inspect.diagnostics").contains(ExternalPrerequisite.of(
                "pip is required by scip-python and must be available with the selected Python runtime")),
                outcome.get("inspect.diagnostics"));
        assertFalse(Files.exists(home.resolve("tools")));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aPipInThePythonScriptsDirectoryCountsEvenWhenItIsNotOnThePath() throws Exception {
        FakeTools.succeeding(tools, "node");
        FakeTools.succeeding(tools, "python");
        FakeTools.succeeding(tools.resolve("Scripts"), "pip");

        ChildJvm.Outcome outcome = child("inspect");

        assertFalse(outcome.get("inspect.diagnostics").contains("pip is required"), outcome.get("inspect.diagnostics"));
    }

    @Test
    void withEveryPrerequisiteButNoInstallationOnlyTheMissingPackageIsReported() throws Exception {
        everyPrerequisite();
        FakeTools.succeeding(tools, "npm");

        ChildJvm.Outcome outcome = child("inspect");

        assertEquals("NOT_INSTALLED", outcome.get("inspect.state"));
        assertEquals("managed scip-python " + VERSION + " is not installed", outcome.get("inspect.diagnostics"));
        assertEquals("absent", outcome.get("inspect.executable"));
    }

    @Test
    void anInstalledPackageWithAMissingPrerequisiteIsBlockedNotNotInstalled() throws Exception {
        Files.createDirectories(installedExecutable().getParent());
        Files.createFile(installedExecutable());

        ChildJvm.Outcome outcome = child("inspect,executor");

        // Nothing is on PATH: the package is there but node, npm and python are not.
        assertEquals("BLOCKED", outcome.get("inspect.state"));
        assertEquals("present", outcome.get("inspect.executable"));
        assertFalse(outcome.get("inspect.diagnostics").contains("is not installed"), outcome.get("inspect.diagnostics"));
        assertTrue(outcome.get("executor.failure").contains("provider runtime is not ready"), outcome.get("executor.failure"));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void onWindowsTheEntryPointAndThePreloadAreRequiredToo() throws Exception {
        everyPrerequisite();
        FakeTools.succeeding(tools, "npm");
        Files.createDirectories(installedExecutable().getParent());
        Files.createFile(installedExecutable());

        ChildJvm.Outcome outcome = child("inspect");

        assertEquals("BLOCKED", outcome.get("inspect.state"));
        assertTrue(outcome.get("inspect.diagnostics").contains("managed scip-python package entry point is missing"),
                outcome.get("inspect.diagnostics"));
        assertTrue(outcome.get("inspect.diagnostics")
                .contains("managed scip-python Windows compatibility preload is missing"), outcome.get("inspect.diagnostics"));
    }

    // ------------------------------------------------------------------ child JVM: installation

    @Test
    void installationRunsALockedNpmCiWithoutScriptsAndWithoutTheCallerSecrets() throws Exception {
        everyPrerequisite();
        fakeNpm(true);

        ChildJvm.Outcome outcome = child("install,executor");

        assertEquals(0, outcome.exitCode(), outcome.stderr());
        String arguments = Files.readString(tools.resolve("npm-args.txt"));
        assertTrue(arguments.contains("ci"), arguments);
        assertTrue(arguments.contains("--prefix"), arguments);
        assertTrue(arguments.contains("--ignore-scripts"), arguments);
        assertTrue(arguments.contains("--no-audit"), arguments);
        assertTrue(arguments.contains("--no-fund"), arguments);
        assertEquals("token=[]", Files.readString(tools.resolve("npm-env.txt")).trim(),
                "the installation environment must not carry the caller's MINOS_* secrets");
        assertFalse(Files.readString(tools.resolve("npm-env.txt")).contains("do-not-leak"));

        Path destination = installedExecutable().getParent().getParent().getParent();
        assertTrue(Files.isRegularFile(installedExecutable()), "the package is moved into place");
        assertTrue(Files.isRegularFile(destination.resolve("package.json")));
        assertTrue(Files.isRegularFile(destination.resolve("package-lock.json")), "the locked tree is what npm ci installs");
        assertFalse(Files.exists(destination.resolveSibling(VERSION + ".partial")), "no partial tree is left behind");
        assertTrue(Files.isRegularFile(home.resolve("tools").resolve("scip-python-install.log")));
        if (FakeTools.windows()) {
            String preload = Files.readString(destination.resolve("minos-windows-regexp-compat.cjs"));
            assertTrue(preload.contains("global.RegExp"), preload);
        }
        assertReadyOrIncomplete(outcome);
    }

    /** READY needs a qualified sandbox on the host: either the installation is READY and an executor exists, or it is refused as incomplete. */
    private static void assertReadyOrIncomplete(ChildJvm.Outcome outcome) {
        if (outcome.get("install.state") != null) {
            assertEquals("READY", outcome.get("install.state"));
            assertEquals("created", outcome.get("executor.result"));
            assertNull(outcome.get("install.failure"));
        } else {
            assertNotNull(outcome.get("install.failure"), outcome.values().toString());
            assertTrue(outcome.get("install.failure").contains("scip-python installation is incomplete"),
                    outcome.get("install.failure"));
            assertTrue(outcome.get("executor.failure").contains("provider runtime is not ready"),
                    outcome.get("executor.failure"));
        }
    }

    @Test
    void aFailingNpmLeavesNeitherAPartialTreeNorAnInstallation() throws Exception {
        everyPrerequisite();
        FakeTools.failing(tools, "npm", "registry unreachable", 3);

        ChildJvm.Outcome outcome = child("install");

        assertTrue(outcome.get("install.failure").contains("tool command failed with code 3"), outcome.get("install.failure"));
        Path versionDirectory = installedExecutable().getParent().getParent().getParent();
        assertFalse(Files.exists(versionDirectory), "a failed installation must not leave a usable tree");
        assertFalse(Files.exists(versionDirectory.resolveSibling(VERSION + ".partial")));
        assertTrue(Files.readString(home.resolve("tools").resolve("scip-python-install.log")).contains("registry unreachable"),
                "the operator can read why npm failed in the install log");
    }

    @Test
    void anNpmThatBuildsNoExecutableIsAFailedInstallation() throws Exception {
        everyPrerequisite();
        FakeTools.succeeding(tools, "npm");

        ChildJvm.Outcome outcome = child("install");

        assertTrue(outcome.get("install.failure").contains("scip-python executable was not created"),
                outcome.get("install.failure"));
        assertFalse(Files.exists(installedExecutable()));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void onWindowsAPackageWithoutItsEntryPointIsRefused() throws Exception {
        everyPrerequisite();
        fakeNpm(false);

        ChildJvm.Outcome outcome = child("install");

        assertTrue(outcome.get("install.failure").contains("scip-python package entry point was not created"),
                outcome.get("install.failure"));
        assertFalse(Files.exists(installedExecutable()));
    }
}
