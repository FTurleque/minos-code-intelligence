package com.minos.adapter.scip.runtime.install;

import com.minos.runtime.ProviderRuntimeStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Archive extraction, classpath resolution and status reporting of the scip-java / scip-typescript manager.
 * Everything here is reachable without a tool on {@code PATH}.
 */
class ManagedScipProviderRuntimeManagerRuntimeTest {

    @TempDir
    Path directory;

    private Path zip(String name, Map<String, String> entries) throws IOException {
        Path archive = directory.resolve(name);
        try (OutputStream output = Files.newOutputStream(archive); ZipOutputStream zip = new ZipOutputStream(output)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                if (!entry.getKey().endsWith("/")) zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return archive;
    }

    // ------------------------------------------------------------------ extractZipBounded

    @Test
    void extractsFilesAndDirectoriesUnderTheDestination() throws IOException {
        Path archive = zip("tool.zip", new java.util.LinkedHashMap<>(Map.of(
                "bin/", "", "bin/tool", "payload", "lib/deep/readme.txt", "hello")));
        Path destination = directory.resolve("out");

        BoundedZipExtraction.extractZipBounded(archive, destination, 1024, 10);

        assertEquals("payload", Files.readString(destination.resolve("bin/tool")));
        assertEquals("hello", Files.readString(destination.resolve("lib/deep/readme.txt")));
    }

    @Test
    void anEntryThatEscapesTheDestinationIsRefusedAndNothingIsWrittenOutside() throws IOException {
        Path archive = zip("slip.zip", Map.of("../evil.txt", "owned"));
        Path destination = directory.resolve("out");

        IOException refusal = assertThrows(IOException.class,
                () -> BoundedZipExtraction.extractZipBounded(archive, destination, 1024, 10));

        assertTrue(refusal.getMessage().contains("archive entry escapes destination"), refusal.getMessage());
        assertFalse(Files.exists(directory.resolve("evil.txt")), "zip-slip must not write outside the destination");
    }

    @Test
    void anArchiveWithMoreEntriesThanTheBudgetIsRefused() throws IOException {
        Path archive = zip("many.zip", new java.util.LinkedHashMap<>(Map.of("a.txt", "a", "b.txt", "b")));
        Path destination = directory.resolve("out");

        IOException refusal = assertThrows(IOException.class,
                () -> BoundedZipExtraction.extractZipBounded(archive, destination, 1024, 1));

        assertTrue(refusal.getMessage().contains("too many entries"), refusal.getMessage());
        assertTrue(refusal.getMessage().endsWith(": many.zip"), refusal.getMessage());
        assertFalse(refusal.getMessage().contains(directory.toString()), refusal.getMessage());
    }

    @Test
    void anArchiveThatExpandsBeyondTheByteBudgetIsRefused() throws IOException {
        Path archive = zip("big.zip", Map.of("big.bin", "x".repeat(4096)));
        Path destination = directory.resolve("out");

        IOException refusal = assertThrows(IOException.class,
                () -> BoundedZipExtraction.extractZipBounded(archive, destination, 100, 10));

        assertTrue(refusal.getMessage().contains("extraction size budget"), refusal.getMessage());
    }

    // ------------------------------------------------------------------ resolvedJars

    @Test
    void theClasspathIsTheLastLineThatNamesOnlyAbsoluteJars() throws IOException {
        Path first = directory.resolve("first.jar");
        Path second = directory.resolve("second.jar");
        Path log = directory.resolve("fetch.log");
        Files.writeString(log, String.join("\n",
                "Downloading https://repo.example/first.jar",
                first.toString(),
                "Resolved in 3s",
                first + java.io.File.pathSeparator + second,
                "done", ""));

        assertEquals(List.of(first, second), ScipJavaClasspathInstallation.resolvedJars(log));
    }

    @Test
    void aLogWithoutAnyClasspathLineIsRefused() throws IOException {
        Path log = directory.resolve("fetch.log");
        Files.writeString(log, "Downloading https://repo.example/first.jar\nnothing else\n");

        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> ScipJavaClasspathInstallation.resolvedJars(log));

        assertEquals("scip-java classpath resolution returned no jar", refusal.getMessage());
    }

    @Test
    void aMissingLogOrADirectoryInsteadOfALogIsRefused() throws IOException {
        Path missing = directory.resolve("absent.log");
        Path folder = Files.createDirectories(directory.resolve("folder.log"));

        assertThrows(IOException.class, () -> ScipJavaClasspathInstallation.resolvedJars(missing));
        assertThrows(IOException.class, () -> ScipJavaClasspathInstallation.resolvedJars(folder));
    }

    @Test
    void theFetchCommandOnlyResolvesTheClasspath() {
        Path coursier = directory.resolve("cs");

        assertEquals(List.of(coursier.toString(), "fetch", "--classpath", ManagedScipProviderRuntimeManager.SCIP_JAVA_COORDINATE),
                ScipJavaClasspathInstallation.scipJavaFetchCommand(coursier));
    }

    // ------------------------------------------------------------------ packaged resources

    @Test
    void aPackagedResourceThatDoesNotExistIsRefusedAndNothingIsWritten() {
        Path destination = directory.resolve("runtime").resolve("missing.ps1");

        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> ManagedScipProviderRuntimeManager.copyPackagedResource("no-such-resource.ps1", destination));

        assertTrue(refusal.getMessage().contains("packaged scip-java runtime resource is missing: no-such-resource.ps1"),
                refusal.getMessage());
        assertFalse(Files.exists(destination));
    }

    @Test
    void copyingAPackagedResourceReplacesWhatWasThereAndLeavesNoPartialFile() throws IOException {
        Path destination = directory.resolve("runtime").resolve("ScipWriter.java");
        Files.createDirectories(destination.getParent());
        Files.writeString(destination, "stale");

        ManagedScipProviderRuntimeManager.copyPackagedResource("ScipWriter.java", destination);

        assertNotEquals("stale", Files.readString(destination));
        assertFalse(Files.exists(destination.resolveSibling("ScipWriter.java.partial")));
    }

    // ------------------------------------------------------------------ git bash

    @Test
    void aGitExecutableWithoutAParentDirectoryOrWithoutBashHasNoGitBash() throws IOException {
        Path git = directory.resolve("Git").resolve("cmd").resolve("git.exe");
        Files.createDirectories(git.getParent());
        Files.createFile(git);

        assertTrue(ManagedScipProviderRuntimeManager.gitBashForGit(Path.of("git.exe")).isEmpty());
        assertTrue(ManagedScipProviderRuntimeManager.gitBashForGit(git).isEmpty());
    }

    // ------------------------------------------------------------------ the manager itself

    @Test
    void listsScipJavaThenScipTypescriptUnderTheirPinnedVersions() {
        List<ProviderRuntimeStatus> statuses = new ManagedScipProviderRuntimeManager(directory).list();

        assertEquals(List.of(ManagedScipProviderRuntimeManager.SCIP_JAVA_ID, ManagedScipProviderRuntimeManager.SCIP_TYPESCRIPT_ID),
                statuses.stream().map(ProviderRuntimeStatus::providerId).toList());
        assertEquals(ManagedScipProviderRuntimeManager.SCIP_JAVA_VERSION, statuses.get(0).version());
        assertEquals(ManagedScipProviderRuntimeManager.SCIP_TYPESCRIPT_VERSION, statuses.get(1).version());
    }

    @Test
    void scipTypescriptIsNotInstalledUntilItsPackageIsThere() {
        ManagedScipProviderRuntimeManager manager = new ManagedScipProviderRuntimeManager(directory);

        ProviderRuntimeStatus status = manager.inspect(ManagedScipProviderRuntimeManager.SCIP_TYPESCRIPT_ID);

        assertEquals(ProviderRuntimeStatus.State.NOT_INSTALLED, status.state());
        assertTrue(status.diagnostics().contains("managed scip-typescript "
                + ManagedScipProviderRuntimeManager.SCIP_TYPESCRIPT_VERSION + " is not installed"), status.diagnostics().toString());
        assertTrue(status.diagnostics().stream().noneMatch(line -> line.contains(directory.toString())),
                "a diagnostic must not carry the absolute home path: " + status.diagnostics());
    }

    @Test
    void noExecutorIsHandedOutForARuntimeThatIsNotReady() {
        ManagedScipProviderRuntimeManager manager = new ManagedScipProviderRuntimeManager(directory);

        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> manager.executor(ManagedScipProviderRuntimeManager.SCIP_TYPESCRIPT_ID));

        assertTrue(refusal.getMessage().contains("provider runtime is not ready: scip-typescript"), refusal.getMessage());
    }

    @Test
    void anUnknownProviderIsRefusedByEveryEntryPoint() {
        ManagedScipProviderRuntimeManager manager = new ManagedScipProviderRuntimeManager(directory);

        assertThrows(IllegalArgumentException.class, () -> manager.inspect("scip-python"));
        assertThrows(IllegalArgumentException.class, () -> manager.install("scip-python"));
        assertThrows(IllegalArgumentException.class, () -> manager.executor("scip-python"));
    }

    @Test
    void theManagedToolsLiveUnderTheToolsDirectoryOfTheHome() {
        ManagedScipProviderRuntimeManager manager = new ManagedScipProviderRuntimeManager(directory);
        Path tools = directory.toAbsolutePath().normalize().resolve("tools");

        assertTrue(manager.mavenExecutable().startsWith(tools.resolve("maven")), manager.mavenExecutable().toString());
        assertTrue(manager.nodeExecutable().startsWith(tools.resolve("nodejs")), manager.nodeExecutable().toString());
        assertTrue(manager.typeScriptMainScript().startsWith(tools.resolve("scip-typescript")));
        assertEquals("main.js", manager.typeScriptMainScript().getFileName().toString());
        assertEquals(tools.resolve("scip-java").resolve(ManagedScipProviderRuntimeManager.SCIP_JAVA_VERSION)
                .resolve("classpath").resolve("classpath.txt"), manager.scipJavaClasspathFile());
    }

    @Test
    void theManagerRequiresAHomeDirectory() {
        assertThrows(NullPointerException.class, () -> new ManagedScipProviderRuntimeManager(null));
    }
}
