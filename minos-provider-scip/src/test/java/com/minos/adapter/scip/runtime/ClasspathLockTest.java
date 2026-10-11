package com.minos.adapter.scip.runtime;

import com.minos.io.Sha256;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L'installation de scip-java résout le classpath sur le poste (coursier fetch) puis le compare, jar par jar, au
 * verrou livré : rien n'est gardé ni exécuté avant cette vérification (AUD-SEC-02).
 */
class ClasspathLockTest {

    private static final byte[] ALPHA = "alpha".getBytes(StandardCharsets.UTF_8);
    private static final byte[] BETA = "beta".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path temp;

    private static String lockJson(String... jars) {
        return "{\"coordinate\":\"org.scip-code:scip-java:0.13.1\",\"version\":\"0.13.1\",\"jars\":[" + String.join(",", jars) + "]}";
    }

    private static String jar(String name, byte[] content) {
        return "{\"name\":\"" + name + "\",\"size\":" + content.length + ",\"sha256\":\"" + Sha256.hex(content) + "\"}";
    }

    private ClasspathLock twoJarLock() throws IOException {
        return ClasspathLock.parse(new StringReader(lockJson(jar("alpha.jar", ALPHA), jar("beta.jar", BETA))));
    }

    private Path jar(String name, String content) throws IOException {
        Path directory = Files.createDirectories(temp.resolve("cache"));
        Path file = directory.resolve(name);
        Files.writeString(file, content);
        return file;
    }

    @Test
    void theResolvedSetEqualToTheLockIsAccepted() throws Exception {
        twoJarLock().verify(List.of(jar("alpha.jar", "alpha"), jar("beta.jar", "beta")));
    }

    @Test
    void aReplacedJarFailsAndNamesOnlyTheFile() throws Exception {
        ClasspathLock lock = twoJarLock();
        List<Path> resolved = List.of(jar("alpha.jar", "alpha"), jar("beta.jar", "BETA-REPLACED"));

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> lock.verify(resolved));

        assertTrue(failure.getMessage().contains("beta.jar"), failure.getMessage());
        assertTrue(failure.getMessage().contains("pinned SHA-256"), failure.getMessage());
        assertFalse(failure.getMessage().contains(temp.toString()), "chemin absolu : " + failure.getMessage());
    }

    @Test
    void aJarOfTheSameSizeButOtherContentFails() throws Exception {
        ClasspathLock lock = twoJarLock();
        List<Path> resolved = List.of(jar("alpha.jar", "alphA"), jar("beta.jar", "beta"));

        assertThrows(IllegalStateException.class, () -> lock.verify(resolved));
    }

    @Test
    void anExtraJarFails() throws Exception {
        ClasspathLock lock = twoJarLock();
        List<Path> resolved = List.of(jar("alpha.jar", "alpha"), jar("beta.jar", "beta"), jar("gamma.jar", "x"));

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> lock.verify(resolved));

        assertTrue(failure.getMessage().contains("unexpected jar gamma.jar"), failure.getMessage());
    }

    @Test
    void aMissingJarFails() throws Exception {
        ClasspathLock lock = twoJarLock();

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> lock.verify(List.of(jar("alpha.jar", "alpha"))));

        assertTrue(failure.getMessage().contains("missing jar beta.jar"), failure.getMessage());
    }

    @Test
    void aMalformedOrEmptyLockIsRefused() {
        for (String broken : List.of("not json", "{}", lockJson(), lockJson("{\"name\":\"a.jar\",\"size\":1,\"sha256\":\"zz\"}"),
                lockJson(jar("a.jar", ALPHA), jar("a.jar", ALPHA)))) {
            assertThrows(IOException.class, () -> ClasspathLock.parse(new StringReader(broken)), broken);
        }
    }

    @Test
    void thePackagedLockPinsScipJavaItself() throws Exception {
        ClasspathLock packaged = ClasspathLock.packaged();

        assertEquals(ManagedScipProviderRuntimeManager.SCIP_JAVA_COORDINATE, packaged.coordinate());
        assertTrue(packaged.names().contains("scip-java-" + ManagedScipProviderRuntimeManager.SCIP_JAVA_VERSION + ".jar"));
        assertTrue(packaged.names().size() > 10, "le verrou doit épingler tout le graphe de dépendances");
    }

    // ------------------------------------------------------------------ l'installation, avec un faux coursier

    private Path fakeCoursier(String classpathLine) throws IOException {
        Path directory = Files.createDirectories(temp.resolve("bin"));
        boolean windows = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win");
        Path script = directory.resolve(windows ? "fake-cs.cmd" : "fake-cs");
        Path arguments = directory.resolve("arguments.txt");
        if (windows) {
            Files.writeString(script, "@echo off\r\necho %*> \"" + arguments + "\"\r\necho Downloading https://repo.example/x.jar\r\necho "
                    + classpathLine + "\r\n");
        } else {
            Files.writeString(script, "#!/bin/sh\necho \"$@\" > '" + arguments + "'\necho 'Downloading https://repo.example/x.jar'\necho '"
                    + classpathLine + "'\n");
            script.toFile().setExecutable(true);
        }
        return script;
    }

    @Test
    void theInstallationFetchesTheClasspathAndNeverLaunchesScipJava() throws Exception {
        Path alpha = jar("alpha.jar", "alpha");
        Path beta = jar("beta.jar", "beta");
        Path coursier = fakeCoursier(alpha + java.io.File.pathSeparator + beta);

        List<Path> jars = ManagedScipProviderRuntimeManager.fetchVerifiedClasspath(
                coursier, temp, temp.resolve("logs").resolve("install.log"), twoJarLock());

        assertEquals(List.of(alpha, beta), jars);
        String arguments = Files.readString(coursier.resolveSibling("arguments.txt")).trim();
        assertEquals("fetch --classpath org.scip-code:scip-java:0.13.1", arguments);
    }

    @Test
    void anInstallationWhoseJarDoesNotMatchTheLockFails() throws Exception {
        Path alpha = jar("alpha.jar", "alpha");
        Path beta = jar("beta.jar", "TAMPERED");
        Path coursier = fakeCoursier(alpha + java.io.File.pathSeparator + beta);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ManagedScipProviderRuntimeManager.fetchVerifiedClasspath(
                        coursier, temp, temp.resolve("logs").resolve("install.log"), twoJarLock()));

        assertTrue(failure.getMessage().contains("beta.jar"), failure.getMessage());
        assertFalse(failure.getMessage().contains(temp.toString()), failure.getMessage());
    }

    @Test
    void aFetchOutputWithoutAnyJarListIsRefused() throws Exception {
        Path coursier = fakeCoursier("nothing to see here");

        assertThrows(IllegalStateException.class, () -> ManagedScipProviderRuntimeManager.fetchVerifiedClasspath(
                coursier, temp, temp.resolve("logs").resolve("install.log"), twoJarLock()));
    }

    @Test
    void aRelativeJarEntryIsNotAClasspath() throws Exception {
        Path log = temp.resolve("relative.log");
        Files.writeString(log, "alpha.jar" + java.io.File.pathSeparator + "beta.jar\n");

        assertThrows(IllegalStateException.class, () -> ManagedScipProviderRuntimeManager.resolvedJars(log));
    }
}
