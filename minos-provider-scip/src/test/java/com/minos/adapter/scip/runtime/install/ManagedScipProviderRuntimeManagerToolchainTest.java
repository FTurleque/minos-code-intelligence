package com.minos.adapter.scip.runtime.install;

import com.minos.adapter.scip.runtime.plan.ScipJavaMavenOfflineFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
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
 * scip-typescript and scip-java against fake {@code node}, {@code npm} and {@code cs} executables. The manager
 * resolves them from {@code PATH}, so each scenario runs in a child JVM whose {@code PATH} is a directory the test
 * wrote (see {@link ChildJvm}). The installation that downloads Node.js or Coursier (Windows) is never reached: it
 * would need the network.
 */
class ManagedScipProviderRuntimeManagerToolchainTest {

    private static final String TYPESCRIPT = ManagedScipProviderRuntimeManager.SCIP_TYPESCRIPT_ID;
    private static final String JAVA = ManagedScipProviderRuntimeManager.SCIP_JAVA_ID;
    private static final Set<String> STATES_AFTER_QUALIFICATION = Set.of("READY", "BLOCKED", "UNSUPPORTED_BY_BACKEND");

    @TempDir
    Path home;

    @TempDir
    Path tools;

    @TempDir
    Path scratch;

    private ChildJvm.Outcome child(String provider, String actions, Map<String, String> environment)
            throws IOException, InterruptedException {
        return ChildJvm.run(tools, scratch, environment, "provider", provider, home.toString(), actions);
    }

    private ChildJvm.Outcome child(String provider, String actions) throws IOException, InterruptedException {
        return child(provider, actions, Map.of());
    }

    private ManagedScipProviderRuntimeManager manager() {
        return new ManagedScipProviderRuntimeManager(home, ScipJavaMavenOfflineFixtures.unconfigured(home));
    }

    /** The scip-typescript package tree as npm leaves it: entry script and command shim. */
    private void typeScriptPackage() throws IOException {
        ManagedScipProviderRuntimeManager manager = manager();
        Files.createDirectories(manager.typeScriptMainScript().getParent());
        Files.writeString(manager.typeScriptMainScript(), "// entry\n");
        Path shimDirectory = home.resolve("tools").resolve(TYPESCRIPT)
                .resolve(ManagedScipProviderRuntimeManager.SCIP_TYPESCRIPT_VERSION).resolve("node_modules").resolve(".bin");
        Files.createDirectories(shimDirectory);
        Files.writeString(shimDirectory.resolve(FakeTools.windows() ? "scip-typescript.cmd" : "scip-typescript"), "shim\n");
    }

    // ------------------------------------------------------------------ scip-typescript

    @Test
    void scipTypescriptInstallationIsRefusedWithoutNpmAndNothingIsInstalled() throws Exception {
        ChildJvm.Outcome outcome = child(TYPESCRIPT, "install,executor");

        assertEquals("IllegalStateException: npm is required to install scip-typescript", outcome.get("install.failure"));
        assertTrue(outcome.get("executor.failure").contains("provider runtime is not ready"), outcome.get("executor.failure"));
        assertFalse(Files.exists(manager().typeScriptMainScript()));
    }

    @Test
    void scipTypescriptInstallationIsRefusedWithoutNodeAfterNpm() throws Exception {
        FakeTools.succeeding(tools, "npm");

        ChildJvm.Outcome outcome = child(TYPESCRIPT, "install");

        assertEquals("IllegalStateException: Node.js is required to run scip-typescript", outcome.get("install.failure"));
        assertFalse(Files.exists(manager().typeScriptMainScript()));
    }

    @Test
    void anInstalledScipTypescriptPackageWithoutItsRuntimeIsBlockedNotNotInstalled() throws Exception {
        typeScriptPackage();

        ChildJvm.Outcome outcome = child(TYPESCRIPT, "inspect,executor");

        // Nothing is on PATH (and no managed Node.js exists): the package is there, its runtime is not.
        assertEquals("BLOCKED", outcome.get("inspect.state"));
        assertFalse(outcome.get("inspect.diagnostics").contains("managed scip-typescript "
                + ManagedScipProviderRuntimeManager.SCIP_TYPESCRIPT_VERSION + " is not installed"),
                outcome.get("inspect.diagnostics"));
        assertTrue(outcome.get("executor.failure").contains("provider runtime is not ready"), outcome.get("executor.failure"));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void onLinuxTheNodeAndNpmOfThePathAreTheOnlyPrerequisitesOfAnInstalledPackage() throws Exception {
        typeScriptPackage();
        FakeTools.succeeding(tools, "node");
        FakeTools.succeeding(tools, "npm");

        ChildJvm.Outcome outcome = child(TYPESCRIPT, "inspect,executor");

        String state = outcome.get("inspect.state");
        assertNotNull(state, outcome.stderr());
        assertTrue(STATES_AFTER_QUALIFICATION.contains(state), state);
        assertEquals("present", outcome.get("inspect.executable"));
        assertEquals("READY".equals(state), outcome.get("executor.result") != null, outcome.values().toString());
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void onLinuxAnNpmInstallBuildsTheLockedPackageWithoutRunningItsScripts() throws Exception {
        FakeTools.succeeding(tools, "node");
        FakeTools.write(tools, "npm", List.of("exit /b 0"), List.of(
                "echo \"$@\" > '" + tools.resolve("npm-args.txt") + "'",
                "/bin/mkdir -p \"$3/node_modules/.bin\" \"$3/node_modules/@sourcegraph/scip-typescript/dist/src\"",
                ": > \"$3/node_modules/.bin/scip-typescript\"",
                ": > \"$3/node_modules/@sourcegraph/scip-typescript/dist/src/main.js\"",
                "exit 0"));

        ChildJvm.Outcome outcome = child(TYPESCRIPT, "install");

        String arguments = Files.readString(tools.resolve("npm-args.txt"));
        assertTrue(arguments.contains("ci --prefix "), arguments);
        assertTrue(arguments.contains("--ignore-scripts"), arguments);
        assertTrue(Files.isRegularFile(manager().typeScriptMainScript()));
        assertFalse(Files.exists(home.resolve("tools").resolve(TYPESCRIPT)
                .resolve(ManagedScipProviderRuntimeManager.SCIP_TYPESCRIPT_VERSION + ".partial")),
                "no partial tree is left behind");
        // installTypeScript() ends on the unqualified inspection of the tree it built.
        assertNull(outcome.get("install.failure"), outcome.get("install.failure"));
        assertEquals("READY", outcome.get("install.state"));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void onLinuxAFailingNpmInstallLeavesNothingBehind() throws Exception {
        FakeTools.succeeding(tools, "node");
        FakeTools.failing(tools, "npm", "registry unreachable", 4);

        ChildJvm.Outcome outcome = child(TYPESCRIPT, "install");

        assertTrue(outcome.get("install.failure").contains("tool command failed with code 4"), outcome.get("install.failure"));
        assertFalse(Files.exists(manager().typeScriptMainScript()));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void onLinuxAnNpmInstallThatCreatesNoExecutableIsRefusedByItsNameAndLeavesNothingBehind() throws Exception {
        FakeTools.succeeding(tools, "node");
        FakeTools.succeeding(tools, "npm");

        ChildJvm.Outcome outcome = child(TYPESCRIPT, "install");

        String failure = outcome.get("install.failure");
        assertNotNull(failure, outcome.stderr());
        assertTrue(failure.endsWith("scip-typescript executable was not created: scip-typescript"), failure);
        assertFalse(failure.contains(home.toString()), failure);
        assertFalse(Files.exists(manager().typeScriptMainScript()));
        assertFalse(Files.exists(home.resolve("tools").resolve(TYPESCRIPT)
                .resolve(ManagedScipProviderRuntimeManager.SCIP_TYPESCRIPT_VERSION + ".partial")),
                "no partial tree is left behind");
    }

    // ------------------------------------------------------------------ scip-java

    @Test
    void scipJavaWithoutCoursierIsNotInstalled() throws Exception {
        ChildJvm.Outcome outcome = child(JAVA, "inspect,executor");

        assertEquals("NOT_INSTALLED", outcome.get("inspect.state"));
        assertEquals("absent", outcome.get("inspect.executable"));
        assertTrue(outcome.get("inspect.diagnostics").contains("Coursier is not installed in MINOS_HOME/tools and was not found in PATH"),
                outcome.get("inspect.diagnostics"));
        assertTrue(outcome.get("executor.failure").contains("provider runtime is not ready"), outcome.get("executor.failure"));
    }

    @Test
    void aCoursierOnThePathIsTheLauncherOfScipJava() throws Exception {
        FakeTools.succeeding(tools, "cs");

        ChildJvm.Outcome outcome = child(JAVA, "inspect");

        assertEquals("present", outcome.get("inspect.executable"));
        assertFalse(outcome.get("inspect.diagnostics").contains("Coursier is not installed"), outcome.get("inspect.diagnostics"));
    }

    @Test
    void aJavaHomeWithoutJavacIsReportedAsAMachinePrerequisite() throws Exception {
        FakeTools.succeeding(tools, "cs");

        ChildJvm.Outcome outcome = child(JAVA, "inspect", Map.of("JAVA_HOME", scratch.resolve("no-jdk-here").toString()));

        assertTrue(outcome.get("inspect.diagnostics").contains(ExternalPrerequisite.of("JAVA_HOME does not contain javac")),
                outcome.get("inspect.diagnostics"));
        assertEquals("present", outcome.get("inspect.executable"));
    }

    @Test
    void coursierAloneIsNotAnInstallationTheVerifiedClasspathIsRequired() throws Exception {
        FakeTools.succeeding(tools, "cs");

        ChildJvm.Outcome outcome = child(JAVA, "inspect,executor");

        assertEquals("NOT_INSTALLED", outcome.get("inspect.state"));
        assertTrue(outcome.get("inspect.diagnostics").contains("managed scip-java 0.13.2 classpath is not installed in MINOS_HOME/tools"),
                outcome.get("inspect.diagnostics"));
        assertTrue(outcome.get("executor.failure").contains("provider runtime is not ready"), outcome.get("executor.failure"));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void onLinuxAStandaloneLauncherAndCoursierWithAJdkAreInstalledSubjectToTheHostSandbox() throws Exception {
        FakeTools.succeeding(tools, "cs");
        FakeTools.succeeding(tools, "scip-java");

        ChildJvm.Outcome outcome = child(JAVA, "inspect,executor");

        String state = outcome.get("inspect.state");
        assertNotNull(state, outcome.stderr());
        assertTrue(STATES_AFTER_QUALIFICATION.contains(state), state);
        assertEquals("READY".equals(state), outcome.get("executor.result") != null, outcome.values().toString());
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void onLinuxAClasspathThatDoesNotMatchTheLockIsNeverKept() throws Exception {
        Path unlistedJar = Files.writeString(scratch.resolve("unlisted.jar"), "not a pinned jar");
        FakeTools.write(tools, "cs", List.of("exit /b 0"), List.of(
                "if [ \"$1\" = \"fetch\" ]; then echo '" + unlistedJar + "'; fi",
                "exit 0"));

        ChildJvm.Outcome outcome = child(JAVA, "install");

        assertNotNull(outcome.get("install.failure"), outcome.values().toString());
        assertTrue(outcome.get("install.failure").startsWith("IllegalStateException: "), outcome.get("install.failure"));
        assertFalse(Files.exists(manager().scipJavaClasspathFile()),
                "the classpath file is written last: its absence proves the verification refused the jars");
    }

    /** The files an installed scip-java leaves under MINOS_HOME on Windows, with an installed Coursier on the PATH. */
    private void installedScipJavaOnWindows() throws IOException {
        FakeTools.succeeding(tools, "cs");
        Path runtime = home.resolve("tools").resolve(JAVA)
                .resolve(ManagedScipProviderRuntimeManager.SCIP_JAVA_VERSION).resolve("runtime");
        Files.createDirectories(runtime);
        Files.writeString(runtime.resolve("scip-java-windows-runner.ps1"), "# runner\n");
        Files.writeString(runtime.resolve("ScipWriter.java"), "// patch\n");
        Path maven = manager().mavenExecutable();
        Files.createDirectories(maven.getParent());
        Files.writeString(maven, "@echo off\r\n");
        Path classpath = manager().scipJavaClasspathFile();
        Files.createDirectories(classpath.getParent());
        Files.writeString(classpath, "a.jar\n");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void onWindowsAnInstalledScipJavaIsBlockedUntilItsSandboxedExecutionIsQualified() throws Exception {
        installedScipJavaOnWindows();

        ChildJvm.Outcome outcome = child(JAVA, "inspect,executor");

        assertEquals("BLOCKED", outcome.get("inspect.state"), outcome.values().toString());
        assertTrue(outcome.get("inspect.diagnostics").contains(
                        ManagedScipProviderRuntimeManager.SANDBOXED_EXECUTION_NOT_QUALIFIED),
                outcome.get("inspect.diagnostics"));
        // The Docker administration plane runs no provider: the diagnostic must not send the user there.
        assertTrue(outcome.get("inspect.diagnostics").contains("no supported way to index Java projects from a Windows host"),
                outcome.get("inspect.diagnostics"));
        assertFalse(outcome.get("inspect.diagnostics").contains("Docker runtime"), outcome.get("inspect.diagnostics"));
        // Indexing is refused before any provider is launched.
        assertTrue(outcome.get("executor.failure").contains("provider runtime is not ready"), outcome.get("executor.failure"));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void onLinuxScipJavaIsNotBlockedByTheWindowsSandboxQualification() throws Exception {
        FakeTools.succeeding(tools, "cs");
        FakeTools.succeeding(tools, "scip-java");

        ChildJvm.Outcome outcome = child(JAVA, "inspect");

        assertFalse(outcome.get("inspect.diagnostics").contains(
                        ManagedScipProviderRuntimeManager.SANDBOXED_EXECUTION_NOT_QUALIFIED),
                outcome.get("inspect.diagnostics"));
    }
}
