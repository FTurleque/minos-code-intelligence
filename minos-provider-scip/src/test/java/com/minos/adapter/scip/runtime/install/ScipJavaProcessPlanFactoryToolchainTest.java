package com.minos.adapter.scip.runtime.install;

import com.minos.adapter.scip.runtime.plan.ScipJavaClasspath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scip-java process plan as the real factory builds it: it reads {@code JAVA_HOME} and looks the launcher up in
 * {@code PATH}, neither of which a test may change in its own JVM, so the plan is built in a child JVM whose
 * environment the test chose (see {@link ChildJvm}). It lives next to the child-JVM support classes, which are
 * package-private.
 */
class ScipJavaProcessPlanFactoryToolchainTest {

    @TempDir
    Path temp;

    @TempDir
    Path tools;

    @TempDir
    Path scratch;

    private Path project() throws IOException {
        Path project = Files.createDirectories(temp.resolve("project"));
        Files.writeString(project.resolve("pom.xml"), "<project/>\n");
        Files.createDirectories(project.resolve("src"));
        Files.writeString(project.resolve("src").resolve("Main.java"), "class Main {}\n");
        return project;
    }

    private Path run() {
        return temp.resolve("run");
    }

    private ChildJvm.Outcome plan(Path project, Path coursier, Path runner, Path maven, Path classpath,
                                  Map<String, String> environment) throws IOException, InterruptedException {
        return plan(project, coursier, runner, maven, classpath, "accept", environment);
    }

    private ChildJvm.Outcome plan(Path project, Path coursier, Path runner, Path maven, Path classpath, String verifier,
                                  Map<String, String> environment) throws IOException, InterruptedException {
        return plan(project, coursier, runner, maven, classpath, verifier, "JAVA", environment);
    }

    private ChildJvm.Outcome plan(Path project, Path coursier, Path runner, Path maven, Path classpath, String verifier,
                                  String language, Map<String, String> environment)
            throws IOException, InterruptedException {
        String paths = String.join(";", coursier.toString(), runner.toString(),
                maven == null ? "-" : maven.toString(), classpath == null ? "-" : classpath.toString(), verifier,
                language);
        return ChildJvm.run(tools, scratch, environment, "javaplan", project.toString(), run().toString(), paths);
    }

    private ChildJvm.Outcome plan(Path coursier, Path maven, Path classpath) throws IOException, InterruptedException {
        return plan(project(), coursier, temp.resolve("runner.ps1"), maven, classpath, Map.of());
    }

    /** An installed classpath as the installation leaves it: jars, launcher, then the listing. */
    private Path installedClasspath() throws IOException {
        Path directory = Files.createDirectories(temp.resolve("classpath"));
        List<String> names = List.of("scip-java-0.13.1.jar", "scala-library-2.13.jar");
        for (String name : names) Files.writeString(directory.resolve(name), name);
        Files.write(directory.resolve(ScipJavaClasspath.LAUNCHER), ScipJavaClasspath.launcherBytes(names));
        return Files.write(directory.resolve(ScipJavaClasspath.LISTING), ScipJavaClasspath.listingBytes(names));
    }

    // ------------------------------------------------------------------ both platforms: the project JDK

    @Test
    void aJavaHomeWithoutJavacIsRefusedBeforeAnythingIsStaged() throws Exception {
        ChildJvm.Outcome outcome = plan(project(), temp.resolve("cs"), temp.resolve("runner.ps1"), null, null,
                Map.of("JAVA_HOME", temp.resolve("no-jdk").toString()));

        assertTrue(outcome.get("plan.failure").startsWith("IllegalStateException: JAVA_HOME does not point to a JDK containing javac"),
                outcome.get("plan.failure"));
        assertFalse(Files.exists(run()), "a refused plan must not prepare the run directory");
    }

    @Test
    void anUnsetJavaHomeIsRefusedBeforeAnythingIsStaged() throws Exception {
        ChildJvm.Outcome outcome = plan(project(), temp.resolve("cs"), temp.resolve("runner.ps1"), null, null,
                Map.of("JAVA_HOME", ""));

        assertEquals("IllegalStateException: scip-java requires a project JDK: JAVA_HOME is not set",
                outcome.get("plan.failure"));
        assertFalse(Files.exists(run()));
    }

    // ------------------------------------------------------------------ Linux

    @Test
    @EnabledOnOs(OS.LINUX)
    void onLinuxAStandaloneLauncherOnThePathIndexesAWritableCopyOfTheProject() throws Exception {
        FakeTools.succeeding(tools, "scip-java");

        ChildJvm.Outcome outcome = plan(temp.resolve("cs"), null, null);

        assertNull(outcome.get("plan.failure"), outcome.get("plan.failure"));
        List<String> command = List.of(outcome.get("plan.command").split(" \\| "));
        assertEquals(6, command.size(), command.toString());
        assertTrue(command.get(0).endsWith("/scip-java"), command.get(0));
        assertEquals(List.of("index", "--output"), command.subList(1, 3));
        assertTrue(command.get(3).endsWith("index.scip"), command.get(3));
        assertEquals("--targetroot", command.get(4));
        assertEquals(run().resolve("scip-java-targetroot").toAbsolutePath().normalize().toString(), command.get(5),
                "scip-java's intermediate files stay in the run, outside the staged copy");
        Path workspace = run().resolve("workspace");
        assertEquals(workspace.toRealPath(), Path.of(outcome.get("plan.workingDirectory")).toRealPath());
        assertTrue(Files.isRegularFile(workspace.resolve("pom.xml")), "the provider works on a staged copy");
        assertTrue(Files.isRegularFile(workspace.resolve("src").resolve("Main.java")));
        assertEquals("PT1H", outcome.get("plan.timeout"));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void onLinuxWithoutAStandaloneLauncherTheVerifiedClasspathRunsThroughItsLauncherNeverThroughCoursier() throws Exception {
        Path coursier = Files.createFile(temp.resolve("cs"));
        Path listing = installedClasspath();

        ChildJvm.Outcome outcome = plan(coursier, null, listing);

        assertNull(outcome.get("plan.failure"), outcome.get("plan.failure"));
        List<String> command = List.of(outcome.get("plan.command").split(" \\| "));
        assertEquals(8, command.size(), command.toString());
        assertEquals(Path.of(System.getProperty("java.home"), "bin", "java").toAbsolutePath().normalize().toString(),
                command.get(0));
        assertEquals("-jar", command.get(1));
        assertEquals(listing.resolveSibling(ScipJavaClasspath.LAUNCHER).toString(), command.get(2));
        assertEquals(List.of("index", "--output"), command.subList(3, 5));
        assertTrue(command.get(5).endsWith("index.scip"), command.get(5));
        assertEquals("--targetroot", command.get(6));
        assertTrue(command.get(7).endsWith("scip-java-targetroot"), command.get(7));
        assertFalse(command.contains(coursier.toString()), "nothing is resolved by coordinate at index time");
        assertFalse(command.contains("launch"));
        assertTrue(Files.isRegularFile(run().resolve("workspace").resolve("pom.xml")));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void onLinuxAnInstalledClasspathWinsOverAStandaloneLauncherOnThePath() throws Exception {
        FakeTools.succeeding(tools, "scip-java");
        Path listing = installedClasspath();

        ChildJvm.Outcome outcome = plan(temp.resolve("cs"), null, listing);

        assertNull(outcome.get("plan.failure"), outcome.get("plan.failure"));
        List<String> command = List.of(outcome.get("plan.command").split(" \\| "));
        assertEquals("-jar", command.get(1), command.toString());
        assertEquals(listing.resolveSibling(ScipJavaClasspath.LAUNCHER).toString(), command.get(2));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void onLinuxAJarThatNoLongerMatchesTheLockIsRefusedBeforeAnythingIsStaged() throws Exception {
        ChildJvm.Outcome outcome = plan(project(), temp.resolve("cs"), temp.resolve("runner.ps1"), null,
                installedClasspath(), "refuse", Map.of());

        assertEquals("IllegalStateException: scip-java classpath verification failed: scip-java-0.13.1.jar does not match "
                + "its pinned SHA-256; run `minos tools install scip-java`", outcome.get("plan.failure"));
        assertNull(outcome.get("plan.command"));
        assertFalse(Files.exists(run().resolve("workspace")), "a refused classpath must not stage the project");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void onLinuxALauncherThatDoesNotMatchTheListingIsRefused() throws Exception {
        Path listing = installedClasspath();
        Files.writeString(listing.resolveSibling(ScipJavaClasspath.LAUNCHER), "not the derived launcher");

        ChildJvm.Outcome outcome = plan(temp.resolve("cs"), null, listing);

        assertEquals("IllegalStateException: scip-java classpath verification failed: scip-java-launcher.jar does not "
                + "match the verified classpath; run `minos tools install scip-java`", outcome.get("plan.failure"));
        assertNull(outcome.get("plan.command"));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void onLinuxWithNeitherAStandaloneLauncherNorAnInstalledClasspathThePlanIsRefused() throws Exception {
        ChildJvm.Outcome outcome = plan(Files.createFile(temp.resolve("cs")), null, temp.resolve("absent-classpath.txt"));

        assertEquals("IllegalStateException: managed scip-java classpath is not installed; run `minos tools install scip-java`",
                outcome.get("plan.failure"));
        assertNull(outcome.get("plan.command"));
    }

    // ------------------------------------------------------------------ Windows

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void onWindowsAMissingCoursierIsRefused() throws Exception {
        ChildJvm.Outcome outcome = plan(temp.resolve("absent-cs.exe"), null, null);

        assertTrue(outcome.get("plan.failure").contains("Coursier executable is missing"), outcome.get("plan.failure"));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void onWindowsAMissingRunnerIsRefused() throws Exception {
        Path coursier = Files.createFile(temp.resolve("cs.exe"));

        ChildJvm.Outcome outcome = plan(coursier, null, null);

        assertTrue(outcome.get("plan.failure").contains("managed scip-java Windows runner is missing"),
                outcome.get("plan.failure"));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void onWindowsWithoutPowerShellThePlanIsRefused() throws Exception {
        Path coursier = Files.createFile(temp.resolve("cs.exe"));
        Files.createFile(temp.resolve("runner.ps1"));

        ChildJvm.Outcome outcome = plan(coursier, null, null);

        assertTrue(outcome.get("plan.failure").contains("PowerShell (powershell.exe or pwsh.exe) is required"),
                outcome.get("plan.failure"));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void onWindowsWithoutTheManagedMavenThePlanIsRefusedWithTheRepairCommand() throws Exception {
        Path coursier = Files.createFile(temp.resolve("cs.exe"));
        Files.createFile(temp.resolve("runner.ps1"));
        FakeTools.succeeding(tools, "powershell");

        ChildJvm.Outcome outcome = plan(coursier, temp.resolve("absent-mvn.cmd"), null);

        assertTrue(outcome.get("plan.failure").contains("minos tools install scip-java"), outcome.get("plan.failure"));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void onWindowsThePlanRunsTheManagedRunnerWithTheManagedMavenAndThePreResolvedClasspath() throws Exception {
        Path coursier = Files.createFile(temp.resolve("cs.exe"));
        Files.createFile(temp.resolve("runner.ps1"));
        Path maven = Files.createFile(temp.resolve("mvn.cmd"));
        Path classpath = Files.writeString(temp.resolve("classpath.txt"), "a.jar\n");
        FakeTools.succeeding(tools, "powershell");

        ChildJvm.Outcome outcome = plan(coursier, maven, classpath);

        assertNull(outcome.get("plan.failure"), outcome.get("plan.failure"));
        String command = outcome.get("plan.command");
        assertTrue(command.contains("-File") && command.contains("runner.ps1"), command);
        assertTrue(command.contains("-Language") && command.contains("JAVA"), command);
        assertTrue(command.contains("-MavenCommand") && command.contains("mvn.cmd"), command);
        assertTrue(command.contains("-ClasspathFile") && command.contains("classpath.txt"), command);
        assertTrue(outcome.get("plan.artifact").endsWith("scip-java-output" + java.io.File.separator + "index.scip"),
                outcome.get("plan.artifact"));
        assertTrue(Files.isDirectory(run().resolve("scip-java-output")));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void onWindowsAKotlinProjectGivesItsLanguageToTheRunner() throws Exception {
        // AUD-TST-18: this half of ManagedScipProviderRuntimeManagerTest ran in the test JVM and read the JAVA_HOME of
        // the host; it runs here, in a child JVM whose environment the test sets.
        Path coursier = Files.createFile(temp.resolve("cs.exe"));
        Files.createFile(temp.resolve("runner.ps1"));
        Path maven = Files.createFile(temp.resolve("mvn.cmd"));
        Path classpath = Files.writeString(temp.resolve("classpath.txt"), "a.jar\n");
        FakeTools.succeeding(tools, "powershell");

        ChildJvm.Outcome outcome = plan(project(), coursier, temp.resolve("runner.ps1"), maven, classpath, "accept",
                "KOTLIN", Map.of());

        assertNull(outcome.get("plan.failure"), outcome.get("plan.failure"));
        // On Windows the PowerShell invocation may reach the plan as one quoted cmd.exe argument: read the words in order.
        String command = outcome.get("plan.command").replace("\"", "");
        assertTrue(command.matches("(?s).*-Language( \\| | )KOTLIN( .*|$)"), command);
        assertTrue(command.matches("(?s).*-MavenCommand( \\| | )" + Pattern.quote(maven.toString()) + "( .*|$)"),
                command);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void onWindowsAMissingClasspathIsRefusedRatherThanResolvedByTheRunner() throws Exception {
        Path coursier = Files.createFile(temp.resolve("cs.exe"));
        Files.createFile(temp.resolve("runner.ps1"));
        Path maven = Files.createFile(temp.resolve("mvn.cmd"));
        FakeTools.succeeding(tools, "powershell");

        ChildJvm.Outcome outcome = plan(coursier, maven, temp.resolve("absent-classpath.txt"));

        assertEquals("IllegalStateException: managed scip-java classpath is not installed; run `minos tools install scip-java`",
                outcome.get("plan.failure"));
        assertNull(outcome.get("plan.command"));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void onWindowsAJarThatNoLongerMatchesTheLockIsRefused() throws Exception {
        Path coursier = Files.createFile(temp.resolve("cs.exe"));
        Files.createFile(temp.resolve("runner.ps1"));
        Path maven = Files.createFile(temp.resolve("mvn.cmd"));
        FakeTools.succeeding(tools, "powershell");

        ChildJvm.Outcome outcome = plan(project(), coursier, temp.resolve("runner.ps1"), maven, installedClasspath(),
                "refuse", Map.of());

        assertEquals("IllegalStateException: scip-java classpath verification failed: scip-java-0.13.1.jar does not match "
                + "its pinned SHA-256; run `minos tools install scip-java`", outcome.get("plan.failure"));
        assertNull(outcome.get("plan.command"));
    }
}
