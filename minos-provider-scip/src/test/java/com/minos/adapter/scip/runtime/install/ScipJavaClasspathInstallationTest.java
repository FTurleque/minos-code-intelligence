package com.minos.adapter.scip.runtime.install;

import com.minos.adapter.scip.runtime.plan.ScipJavaClasspath;
import com.minos.io.Sha256;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L'installation de scip-java range dans MINOS_HOME/tools une copie vérifiée des jars, le lanceur et la liste : le
 * plan d'indexation ne lit plus le cache de Coursier et revérifie ce qu'il exécute (AUD-SEC-02, résidu D2).
 */
class ScipJavaClasspathInstallationTest {

    private static final byte[] SCIP = "scip-java bytes".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SCALA = "scala bytes".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path temp;

    private ManagedScipProviderRuntimeManager manager() {
        return new ManagedScipProviderRuntimeManager(temp.resolve("home"));
    }

    private static String pinned(String name, byte[] content) {
        return "{\"name\":\"" + name + "\",\"size\":" + content.length + ",\"sha256\":\"" + Sha256.hex(content) + "\"}";
    }

    private static ClasspathLock lock(String... jars) throws IOException {
        return ClasspathLock.parse(new StringReader("{\"coordinate\":\"org.scip-code:scip-java:0.13.1\",\"version\":\"0.13.1\","
                + "\"jars\":[" + String.join(",", jars) + "]}"));
    }

    private static ClasspathLock lock() throws IOException {
        return lock(pinned("scip-java-0.13.1.jar", SCIP), pinned("scala-library-2.13.16.jar", SCALA));
    }

    /** The jars as {@code coursier fetch} leaves them: absolute paths into the cache of the user. */
    private List<Path> cache() throws IOException {
        Path cache = Files.createDirectories(temp.resolve("coursier-cache").resolve("org").resolve("scip"));
        return List.of(Files.write(cache.resolve("scip-java-0.13.1.jar"), SCIP),
                Files.write(cache.resolve("scala-library-2.13.16.jar"), SCALA));
    }

    @Test
    void theVerifiedJarsAreCopiedBesideARelativeListingAndTheirLauncher() throws IOException {
        ManagedScipProviderRuntimeManager manager = manager();

        manager.installClasspath(cache(), lock());

        Path listing = manager.scipJavaClasspathFile();
        Path directory = listing.getParent();
        assertEquals("scip-java-0.13.1.jar\nscala-library-2.13.16.jar\n", Files.readString(listing));
        assertArrayEquals(SCIP, Files.readAllBytes(directory.resolve("scip-java-0.13.1.jar")));
        assertArrayEquals(SCALA, Files.readAllBytes(directory.resolve("scala-library-2.13.16.jar")));
        assertArrayEquals(ScipJavaClasspath.launcherBytes(List.of("scip-java-0.13.1.jar", "scala-library-2.13.16.jar")),
                Files.readAllBytes(directory.resolve(ScipJavaClasspath.LAUNCHER)));
        assertFalse(Files.exists(directory.resolveSibling(directory.getFileName() + ".partial")));
        assertTrue(manager.installedClasspathVerifies(lock()));
    }

    @Test
    void theIndexPlanNeverReadsTheCacheAgainAnAlteredCacheChangesNothing() throws IOException {
        ManagedScipProviderRuntimeManager manager = manager();
        List<Path> cache = cache();
        manager.installClasspath(cache, lock());

        Files.writeString(cache.getFirst(), "replaced in the cache after the installation");

        assertTrue(manager.installedClasspathVerifies(lock()));
        assertTrue(ScipJavaClasspath.readListing(manager.scipJavaClasspathFile()).stream().noneMatch(name -> name.contains("cache")));
    }

    @Test
    void aCopyReplacedAfterTheInstallationNoLongerVerifies() throws IOException {
        ManagedScipProviderRuntimeManager manager = manager();
        manager.installClasspath(cache(), lock());

        Files.writeString(manager.scipJavaClasspathFile().resolveSibling("scip-java-0.13.1.jar"), "replaced");

        assertFalse(manager.installedClasspathVerifies(lock()));
    }

    @Test
    void aJarThatDoesNotMatchTheLockIsNeverKeptAndThePreviousTreeSurvives() throws IOException {
        ManagedScipProviderRuntimeManager manager = manager();
        manager.installClasspath(cache(), lock());
        byte[] previousListing = Files.readAllBytes(manager.scipJavaClasspathFile());
        ClasspathLock other = lock(pinned("scip-java-0.13.1.jar", "other".getBytes(StandardCharsets.UTF_8)),
                pinned("scala-library-2.13.16.jar", SCALA));

        List<Path> cache = cache();
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> manager.installClasspath(cache, other));

        assertEquals("scip-java classpath verification failed: scip-java-0.13.1.jar does not match its pinned SHA-256",
                failure.getMessage());
        assertArrayEquals(previousListing, Files.readAllBytes(manager.scipJavaClasspathFile()));
        Path directory = manager.scipJavaClasspathFile().getParent();
        assertFalse(Files.exists(directory.resolveSibling(directory.getFileName() + ".partial")),
                "a refused installation leaves no partial tree");
    }

    @Test
    void aResolvedEntryThatIsNotAJarFileNameIsRefusedBeforeAnyCopy() throws IOException {
        ManagedScipProviderRuntimeManager manager = manager();
        Path odd = Files.writeString(temp.resolve("odd name.jar"), "x");

        List<Path> resolved = List.of(odd);
        ClasspathLock lock = lock();
        assertThrows(IllegalStateException.class, () -> manager.installClasspath(resolved, lock));
        assertFalse(Files.exists(manager.scipJavaClasspathFile()));
    }

    @Test
    void aSecondInstallationReplacesTheTreeAndLeavesNothingAside() throws IOException {
        ManagedScipProviderRuntimeManager manager = manager();
        manager.installClasspath(cache(), lock());

        manager.installClasspath(cache(), lock());

        Path directory = manager.scipJavaClasspathFile().getParent();
        assertTrue(manager.installedClasspathVerifies(lock()));
        assertFalse(Files.exists(directory.resolveSibling(directory.getFileName() + ".previous")));
        assertFalse(Files.exists(directory.resolveSibling(directory.getFileName() + ".partial")));
    }

    /**
     * The Windows sandbox grants its read access on the classpath directory, inheritable: a file with a protected
     * access list would not receive it and the sandboxed runner could not read the jars.
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void onWindowsEveryInstalledFileInheritsTheAccessOfItsDirectory() throws Exception {
        ManagedScipProviderRuntimeManager manager = manager();
        manager.installClasspath(cache(), lock());

        try (var files = Files.list(manager.scipJavaClasspathFile().getParent())) {
            for (Path file : files.toList()) {
                Process icacls = new ProcessBuilder("icacls", file.toString()).redirectErrorStream(true).start();
                String output = new String(icacls.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(icacls.waitFor(1, TimeUnit.MINUTES));
                List<String> entries = output.lines().filter(line -> line.contains(":(")).toList();
                assertFalse(entries.isEmpty(), output);
                assertTrue(entries.stream().allMatch(entry -> entry.contains("(I)")),
                        file.getFileName() + " has an access entry that is not inherited: " + output);
            }
        }
    }

    @Test
    void aListingOfAnOlderMinosOrNoListingAtAllIsInstalledAgain() throws IOException {
        ManagedScipProviderRuntimeManager manager = manager();
        assertFalse(manager.installedClasspathVerifies(lock()), "nothing installed yet");

        Path listing = manager.scipJavaClasspathFile();
        Files.createDirectories(listing.getParent());
        Files.writeString(listing, String.join("\n", cache().stream().map(Path::toString).toList()) + "\n");

        assertFalse(manager.installedClasspathVerifies(lock()), "absolute paths into the Coursier cache are not kept");
    }
}
