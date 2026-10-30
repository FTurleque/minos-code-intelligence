package com.minos.adapter.scip.runtime.install;

import com.minos.adapter.scip.ScipIndexerCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Go, .NET and Rust runtimes of the polyglot manager against fake toolchains. The manager finds {@code go},
 * {@code dotnet}, {@code cargo}... in {@code PATH}, so each scenario runs in a child JVM whose {@code PATH} is a
 * directory the test wrote (see {@link ChildJvm}); the managed installation trees are built in the test home with
 * the manager's own marker writer.
 */
class ManagedPolyglotScipRuntimeManagerToolchainTest {

    private static final String GO = ScipIndexerCatalog.SCIP_GO_ID;
    private static final String GO_VERSION = ScipIndexerCatalog.SCIP_GO_VERSION;
    private static final String DOTNET = ScipIndexerCatalog.SCIP_DOTNET_ID;
    private static final String DOTNET_VERSION = ScipIndexerCatalog.SCIP_DOTNET_VERSION;
    private static final String RUST = ScipIndexerCatalog.RUST_ANALYZER_SCIP_ID;
    private static final String GO_SOURCE = "proxy.golang.org+sum.golang.org";
    private static final String DOTNET_SOURCE = "nuget.org-pinned-nupkg-sha256";
    private static final Set<String> STATES_AFTER_QUALIFICATION = Set.of("READY", "BLOCKED", "UNSUPPORTED_BY_BACKEND");

    @TempDir
    Path home;

    @TempDir
    Path tools;

    @TempDir
    Path scratch;

    private ChildJvm.Outcome child(String provider, String actions) throws IOException, InterruptedException {
        return ChildJvm.run(tools, scratch, Map.of("MINOS_TEAM_TOKEN", "do-not-leak"), "polyglot", provider,
                home.toString(), actions);
    }

    private Path managedDirectory(String provider, String version) {
        return home.resolve("tools").resolve(provider).resolve(version);
    }

    private static String executableName(String basename) {
        return FakeTools.windows() ? basename + ".exe" : basename;
    }

    /** A managed installation as the installer leaves it: executable, version, source and integrity markers. */
    private Path managedInstallation(String provider, String version, String source) throws IOException {
        Path directory = Files.createDirectories(managedDirectory(provider, version));
        Files.writeString(directory.resolve(executableName(provider)), "binary", StandardCharsets.UTF_8);
        ManagedPolyglotScipRuntimeManager.writeManagedMarkers(directory, version, source);
        return directory;
    }

    /** A fake go: {@code go version} answers, {@code go install} records its call and builds the executable. */
    private void fakeGo(boolean buildsExecutable, int installExitCode) throws IOException {
        List<String> windows = new java.util.ArrayList<>(List.of(
                "if \"%~1\"==\"version\" goto version",
                "echo %* > \"" + tools.resolve("go-args.txt") + "\"",
                "echo gobin=[%GOBIN%] goproxy=[%GOPROXY%] gosumdb=[%GOSUMDB%] token=[%MINOS_TEAM_TOKEN%]> \""
                        + tools.resolve("go-env.txt") + "\""));
        List<String> posix = new java.util.ArrayList<>(List.of(
                "if [ \"$1\" = \"version\" ]; then echo 'go version go1.23.0 linux/amd64'; exit 0; fi",
                "echo \"$@\" > '" + tools.resolve("go-args.txt") + "'",
                "echo \"gobin=[$GOBIN] goproxy=[$GOPROXY] gosumdb=[$GOSUMDB] token=[$MINOS_TEAM_TOKEN]\" > '"
                        + tools.resolve("go-env.txt") + "'"));
        if (installExitCode != 0) {
            windows.add("echo module proxy unreachable");
            posix.add("echo 'module proxy unreachable'");
        }
        if (buildsExecutable) {
            windows.add("echo.> \"%GOBIN%\\scip-go.exe\"");
            posix.add(": > \"$GOBIN/scip-go\"");
        }
        windows.add("exit /b " + installExitCode);
        windows.add(":version");
        windows.add("echo go version go1.23.0 windows/amd64");
        windows.add("exit /b 0");
        posix.add("exit " + installExitCode);
        FakeTools.write(tools, "go", windows, posix);
    }

    /** Whatever the host sandbox says, a runtime that is not READY never hands out an executor, and a READY one does. */
    private void assertQualifiedOrRefused(ChildJvm.Outcome outcome, String action) {
        String state = outcome.get(action + ".state");
        assertNotNull(state, outcome.values().toString() + outcome.stderr());
        assertTrue(STATES_AFTER_QUALIFICATION.contains(state), state);
        assertExecutorMatchesReadiness(outcome, "READY".equals(state));
    }

    private void assertExecutorMatchesReadiness(ChildJvm.Outcome outcome, boolean ready) {
        if (ready) {
            assertEquals("created", outcome.get("executor.result"),
                    "a READY runtime hands out an executor: " + outcome.values() + outcome.stderr());
        } else {
            assertTrue(outcome.get("executor.failure").contains("provider runtime is not ready"),
                    "a runtime that is not READY must never hand out an executor: " + outcome.values());
        }
    }

    // ------------------------------------------------------------------ Go

    @Test
    void goIsBlockedWhenTheToolchainIsMissingAndInstallationIsRefused() throws Exception {
        ChildJvm.Outcome outcome = child(GO, "inspect,install");

        assertEquals("BLOCKED", outcome.get("inspect.state"));
        assertTrue(outcome.get("inspect.diagnostics").startsWith(ExternalPrerequisite.PREFIX),
                outcome.get("inspect.diagnostics"));
        assertEquals("IllegalStateException: go is missing from PATH; install a Go toolchain before scip-go",
                outcome.get("install.failure"));
        assertFalse(Files.exists(home.resolve("tools")));
    }

    @Test
    void goIsBlockedWhenItsVersionProbeFails() throws Exception {
        FakeTools.failing(tools, "go", "broken toolchain", 2);

        ChildJvm.Outcome outcome = child(GO, "inspect");

        assertEquals("BLOCKED", outcome.get("inspect.state"));
        assertTrue(outcome.get("inspect.diagnostics").contains("go version probe failed: broken toolchain"),
                outcome.get("inspect.diagnostics"));
    }

    @Test
    void goWithoutManagedInstallationIsNotInstalled() throws Exception {
        fakeGo(true, 0);

        ChildJvm.Outcome outcome = child(GO, "inspect,executor");

        assertEquals("NOT_INSTALLED", outcome.get("inspect.state"));
        assertEquals("absent", outcome.get("inspect.executable"));
        assertTrue(outcome.get("inspect.diagnostics").contains("managed scip-go " + GO_VERSION + " is not installed"),
                outcome.get("inspect.diagnostics"));
        assertTrue(outcome.get("executor.failure").contains("provider runtime is not ready"), outcome.get("executor.failure"));
    }

    @Test
    void aVerifiedManagedGoInstallationIsReportedWithItsProvenance() throws Exception {
        fakeGo(true, 0);
        managedInstallation(GO, GO_VERSION, GO_SOURCE);

        ChildJvm.Outcome outcome = child(GO, "inspect,executor");

        assertQualifiedOrRefused(outcome, "inspect");
        assertEquals("present", outcome.get("inspect.executable"));
        assertTrue(outcome.get("inspect.diagnostics").contains("proxy.golang.org + sum.golang.org enforced"),
                outcome.get("inspect.diagnostics"));
    }

    @Test
    void aManagedGoInstallationWhoseFilesChangedAfterInstallationIsInvalid() throws Exception {
        fakeGo(true, 0);
        Path directory = managedInstallation(GO, GO_VERSION, GO_SOURCE);
        Files.writeString(directory.resolve(executableName("scip-go")), "swapped binary", StandardCharsets.UTF_8);

        ChildJvm.Outcome outcome = child(GO, "inspect,executor");

        assertEquals("INVALID", outcome.get("inspect.state"));
        assertTrue(outcome.get("inspect.diagnostics").contains("provenance/integrity verification failed"),
                outcome.get("inspect.diagnostics"));
        assertTrue(outcome.get("executor.failure").contains("provider runtime is not ready"), outcome.get("executor.failure"));
    }

    @Test
    void aManagedGoInstallationFromAnotherSourceIsInvalid() throws Exception {
        fakeGo(true, 0);
        managedInstallation(GO, GO_VERSION, "some-other-mirror");

        ChildJvm.Outcome outcome = child(GO, "inspect");

        assertEquals("INVALID", outcome.get("inspect.state"));
    }

    @Test
    void aManagedGoInstallationOfAnotherVersionCountsAsNotInstalled() throws Exception {
        fakeGo(true, 0);
        Path directory = managedInstallation(GO, GO_VERSION, GO_SOURCE);
        Files.writeString(directory.resolve(".minos-version"), "0.0.1", StandardCharsets.UTF_8);

        ChildJvm.Outcome outcome = child(GO, "inspect");

        assertEquals("NOT_INSTALLED", outcome.get("inspect.state"));
    }

    @Test
    void installingGoPinsTheModuleVersionTheProxyAndTheChecksumDatabaseWithoutLeakingSecrets() throws Exception {
        fakeGo(true, 0);

        ChildJvm.Outcome outcome = child(GO, "install,executor");

        assertEquals(0, outcome.exitCode(), outcome.stderr());
        assertEquals("install github.com/scip-code/scip-go/cmd/scip-go@v" + GO_VERSION,
                Files.readString(tools.resolve("go-args.txt")).replace("\"", "").trim());
        String environment = Files.readString(tools.resolve("go-env.txt")).trim();
        assertTrue(environment.contains("goproxy=[https://proxy.golang.org]"), environment);
        assertTrue(environment.contains("gosumdb=[sum.golang.org]"), environment);
        assertTrue(environment.contains("token=[]"), "the caller's MINOS_* secret must not reach go: " + environment);
        assertFalse(environment.contains("do-not-leak"));
        Path directory = managedDirectory(GO, GO_VERSION);
        assertTrue(Files.isRegularFile(directory.resolve(executableName("scip-go"))));
        assertEquals(GO_VERSION, Files.readString(directory.resolve(".minos-version")));
        assertEquals(GO_SOURCE, Files.readString(directory.resolve(".minos-install-source")));
        assertTrue(Files.isRegularFile(directory.resolve(".minos-integrity.sha256")));
        assertFalse(Files.exists(directory.resolveSibling(GO_VERSION + ".partial")));
        assertNull(outcome.get("install.failure"), outcome.get("install.failure"));
        // install() reports the installed tree as it is; the host sandbox only qualifies it on the next inspection.
        assertEquals("READY", outcome.get("install.state"));
        assertEquals("present", outcome.get("install.executable"));
        assertTrue(outcome.get("executor.result") != null || outcome.get("executor.failure") != null, outcome.values().toString());
        assertExecutorMatchesReadiness(outcome, outcome.get("executor.result") != null);
    }

    @Test
    void aFailingGoInstallLeavesNothingAndExplainsWhy() throws Exception {
        fakeGo(false, 5);

        ChildJvm.Outcome outcome = child(GO, "install");

        assertTrue(outcome.get("install.failure").startsWith("IllegalStateException: scip-go install failed: "),
                outcome.get("install.failure"));
        assertTrue(outcome.get("install.failure").contains("module proxy unreachable"), outcome.get("install.failure"));
        assertFalse(Files.exists(managedDirectory(GO, GO_VERSION)));
        assertFalse(Files.exists(managedDirectory(GO, GO_VERSION).resolveSibling(GO_VERSION + ".partial")));
    }

    @Test
    void aGoInstallThatBuildsNoExecutableIsRefusedAndCleaned() throws Exception {
        fakeGo(false, 0);

        ChildJvm.Outcome outcome = child(GO, "install");

        assertTrue(outcome.get("install.failure").contains("scip-go installation completed without executable"),
                outcome.get("install.failure"));
        assertFalse(Files.exists(managedDirectory(GO, GO_VERSION)));
        assertFalse(Files.exists(managedDirectory(GO, GO_VERSION).resolveSibling(GO_VERSION + ".partial")));
    }

    // ------------------------------------------------------------------ .NET

    @Test
    void dotnetIsBlockedAndNotInstallableWithoutTheSdk() throws Exception {
        ChildJvm.Outcome outcome = child(DOTNET, "inspect,install");

        assertEquals("BLOCKED", outcome.get("inspect.state"));
        assertTrue(outcome.get("inspect.diagnostics").startsWith(ExternalPrerequisite.PREFIX),
                outcome.get("inspect.diagnostics"));
        assertEquals("IllegalStateException: dotnet is missing from PATH; install .NET SDK 10+ before scip-dotnet",
                outcome.get("install.failure"));
        assertFalse(Files.exists(home.resolve("tools")));
    }

    @Test
    void anOlderDotnetSdkBlocksInspectionAndInstallationBeforeAnyDownload() throws Exception {
        FakeTools.printing(tools, "dotnet", "9.0.100");

        ChildJvm.Outcome outcome = child(DOTNET, "inspect,install");

        assertEquals("BLOCKED", outcome.get("inspect.state"));
        assertTrue(outcome.get("inspect.diagnostics").contains(".NET SDK 10+"), outcome.get("inspect.diagnostics"));
        assertEquals("IllegalStateException: scip-dotnet " + DOTNET_VERSION
                + " requires .NET SDK 10+; dotnet --version=9.0.100", outcome.get("install.failure"));
        assertFalse(Files.exists(managedDirectory(DOTNET, DOTNET_VERSION)));
    }

    @Test
    void aDotnetThatCannotReportItsVersionIsBlocked() throws Exception {
        FakeTools.failing(tools, "dotnet", "no sdk installed", 1);

        ChildJvm.Outcome outcome = child(DOTNET, "inspect");

        assertEquals("BLOCKED", outcome.get("inspect.state"));
    }

    @Test
    void aDotnetWhoseVersionIsNotANumberIsBlocked() throws Exception {
        FakeTools.printing(tools, "dotnet", "preview");

        ChildJvm.Outcome outcome = child(DOTNET, "inspect");

        assertEquals("BLOCKED", outcome.get("inspect.state"));
    }

    @Test
    void dotnetWithTheSdkButNoManagedToolIsNotInstalled() throws Exception {
        FakeTools.printing(tools, "dotnet", "10.0.100");

        ChildJvm.Outcome outcome = child(DOTNET, "inspect,executor");

        assertEquals("NOT_INSTALLED", outcome.get("inspect.state"));
        assertTrue(outcome.get("executor.failure").contains("provider runtime is not ready"), outcome.get("executor.failure"));
    }

    @Test
    void aVerifiedManagedDotnetToolReportsTheSdkItWasCheckedWith() throws Exception {
        FakeTools.printing(tools, "dotnet", "10.0.100");
        managedInstallation(DOTNET, DOTNET_VERSION, DOTNET_SOURCE);

        ChildJvm.Outcome outcome = child(DOTNET, "inspect,executor");

        assertQualifiedOrRefused(outcome, "inspect");
        assertTrue(outcome.get("inspect.diagnostics").contains("dotnet SDK=10.0.100"), outcome.get("inspect.diagnostics"));
    }

    @Test
    void aManagedDotnetToolWithoutItsIntegrityMarkerIsInvalid() throws Exception {
        FakeTools.printing(tools, "dotnet", "11.0.0");
        Path directory = managedInstallation(DOTNET, DOTNET_VERSION, DOTNET_SOURCE);
        Files.delete(directory.resolve(".minos-integrity.sha256"));

        ChildJvm.Outcome outcome = child(DOTNET, "inspect");

        assertEquals("INVALID", outcome.get("inspect.state"));
        assertEquals("present", outcome.get("inspect.executable"));
    }

    // ------------------------------------------------------------------ Rust

    @Test
    void rustNamesEveryMissingComponent() throws Exception {
        FakeTools.succeeding(tools, "cargo");

        ChildJvm.Outcome outcome = child(RUST, "inspect,executor");

        assertEquals("BLOCKED", outcome.get("inspect.state"));
        assertTrue(outcome.get("inspect.diagnostics").endsWith("missing Rust runtime requirements: rustc, rust-analyzer"),
                outcome.get("inspect.diagnostics"));
        assertTrue(outcome.get("executor.failure").contains("provider runtime is not ready"), outcome.get("executor.failure"));
    }

    @Test
    void rustAnalyzerReportingAnotherVersionIsInvalid() throws Exception {
        FakeTools.succeeding(tools, "cargo");
        FakeTools.succeeding(tools, "rustc");
        FakeTools.printing(tools, "rust-analyzer", "rust-analyzer 0.0.1");

        ChildJvm.Outcome outcome = child(RUST, "inspect");

        assertEquals("INVALID", outcome.get("inspect.state"));
        assertEquals("present", outcome.get("inspect.executable"));
        assertTrue(outcome.get("inspect.diagnostics").contains("version probe must report "
                + ScipIndexerCatalog.RUST_ANALYZER_SCIP_VERSION), outcome.get("inspect.diagnostics"));
    }

    @Test
    void rustAnalyzerAtThePinnedVersionIsAcceptedAsOperatorManaged() throws Exception {
        FakeTools.succeeding(tools, "cargo");
        FakeTools.succeeding(tools, "rustc");
        FakeTools.printing(tools, "rust-analyzer", "rust-analyzer " + ScipIndexerCatalog.RUST_ANALYZER_SCIP_VERSION);

        ChildJvm.Outcome outcome = child(RUST, "inspect,executor");

        assertQualifiedOrRefused(outcome, "inspect");
        assertTrue(outcome.get("inspect.diagnostics").contains("operator-managed rust-analyzer v"
                + ScipIndexerCatalog.RUST_ANALYZER_SCIP_VERSION), outcome.get("inspect.diagnostics"));
    }
}
