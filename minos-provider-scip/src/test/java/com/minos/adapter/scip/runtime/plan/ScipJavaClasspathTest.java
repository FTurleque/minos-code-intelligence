package com.minos.adapter.scip.runtime.plan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Le classpath de scip-java gardé dans MINOS_HOME/tools : la liste, le lanceur qui en dérive, et la vérification que
 * le plan d'indexation refait avant chaque exécution (AUD-SEC-02, résidu D2).
 */
class ScipJavaClasspathTest {

    private static final List<String> NAMES = List.of("scip-java-0.13.1.jar", "scala-library-2.13.16.jar");
    private static final ScipJavaClasspath.Verifier ACCEPT = jars -> { };

    @TempDir
    Path temp;

    private Path installed(List<String> names) throws IOException {
        Path directory = Files.createDirectories(temp.resolve("classpath"));
        for (String name : names) Files.writeString(directory.resolve(name), name);
        Files.write(directory.resolve(ScipJavaClasspath.LAUNCHER), ScipJavaClasspath.launcherBytes(names));
        return Files.write(directory.resolve(ScipJavaClasspath.LISTING), ScipJavaClasspath.listingBytes(names));
    }

    @Test
    void theListingNamesTheJarsOnePerLineAndReadsBackInOrder() throws IOException {
        Path listing = Files.write(temp.resolve(ScipJavaClasspath.LISTING), ScipJavaClasspath.listingBytes(NAMES));

        assertEquals("scip-java-0.13.1.jar\nscala-library-2.13.16.jar\n", Files.readString(listing));
        assertEquals(NAMES, ScipJavaClasspath.readListing(listing));
    }

    @Test
    void blankLinesAndSurroundingSpacesOfAListingAreIgnored() throws IOException {
        Path listing = Files.writeString(temp.resolve("classpath.txt"), "\r\n  scip-java-0.13.1.jar  \r\n\r\nscala-library-2.13.16.jar");

        assertEquals(NAMES, ScipJavaClasspath.readListing(listing));
    }

    @Test
    void onlyPlainJarFileNamesAreAccepted() {
        for (String refused : new String[] {
                "../scip-java.jar", "/opt/cache/scip-java.jar", "C:\\cache\\scip-java.jar", "sub/scip-java.jar",
                "scip java.jar", "scip-java.zip", ".jar", "-x.jar", ""}) {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> ScipJavaClasspath.requireJarName(refused), refused);
            assertFalse(failure.getMessage().contains("cache"), "a refused entry is never echoed: " + failure.getMessage());
        }
        assertThrows(IllegalStateException.class, () -> ScipJavaClasspath.requireJarName(null));
        assertEquals("jackson-core-2.17.1.jar", ScipJavaClasspath.requireJarName("jackson-core-2.17.1.jar"));
        assertEquals("scala3-library_3-3.3.4+1.jar", ScipJavaClasspath.requireJarName("scala3-library_3-3.3.4+1.jar"));
    }

    @Test
    void aListingWrittenByAnOlderMinosWithAbsoluteCachePathsIsRefused() throws IOException {
        Path listing = Files.writeString(temp.resolve("classpath.txt"), temp.resolve("cache").resolve("scip-java.jar") + "\n");

        assertThrows(IllegalStateException.class, () -> ScipJavaClasspath.readListing(listing));
    }

    @Test
    void anEmptyListingOrADuplicatedJarIsRefused() throws IOException {
        Path empty = Files.writeString(temp.resolve("empty.txt"), "\n\n");
        Path twice = Files.writeString(temp.resolve("twice.txt"), "a.jar\nb.jar\na.jar\n");

        assertEquals("the managed scip-java classpath names no jar",
                assertThrows(IllegalStateException.class, () -> ScipJavaClasspath.readListing(empty)).getMessage());
        assertEquals("the managed scip-java classpath names a.jar twice",
                assertThrows(IllegalStateException.class, () -> ScipJavaClasspath.readListing(twice)).getMessage());
    }

    @Test
    void anOversizedListingIsRefusedWithoutBeingMaterialized() throws IOException {
        Path huge = Files.writeString(temp.resolve("huge.txt"), "a.jar\n".repeat(20_000));

        assertThrows(IOException.class, () -> ScipJavaClasspath.readListing(huge));
    }

    @Test
    void theLauncherIsTheSameBytesEveryTimeAndNamesTheMainClassAndTheJars() throws IOException {
        byte[] first = ScipJavaClasspath.launcherBytes(NAMES);
        assertArrayEquals(first, ScipJavaClasspath.launcherBytes(List.copyOf(NAMES)));

        Path launcher = Files.write(temp.resolve("launcher.jar"), first);
        try (JarFile jar = new JarFile(launcher.toFile())) {
            Attributes attributes = jar.getManifest().getMainAttributes();
            assertEquals("org.scip_code.scip_java.ScipJava", attributes.getValue(Attributes.Name.MAIN_CLASS));
            assertEquals("scip-java-0.13.1.jar scala-library-2.13.16.jar", attributes.getValue(Attributes.Name.CLASS_PATH));
            assertEquals(1, jar.size(), "the launcher holds its manifest and nothing else");
        }
        assertFalse(java.util.Arrays.equals(first, ScipJavaClasspath.launcherBytes(List.of("scip-java-0.13.1.jar"))));
    }

    @Test
    void javaRunsTheMainClassOfTheJarsThroughTheLauncher() throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "the tests run on a JDK");
        Path sources = Files.createDirectories(temp.resolve("src/org/scip_code/scip_java"));
        Files.writeString(sources.resolve("ScipJava.java"), """
                package org.scip_code.scip_java;
                public final class ScipJava {
                    public static void main(String[] args) {
                        System.out.print("scip-java " + String.join(" ", args));
                    }
                }
                """);
        Path classes = Files.createDirectories(temp.resolve("classes"));
        assertEquals(0, compiler.run(null, null, null, "-d", classes.toString(),
                sources.resolve("ScipJava.java").toString()));
        Path directory = Files.createDirectories(temp.resolve("classpath"));
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(directory.resolve("scip-java-0.13.1.jar")))) {
            jar.putNextEntry(new JarEntry("org/scip_code/scip_java/ScipJava.class"));
            jar.write(Files.readAllBytes(classes.resolve("org/scip_code/scip_java/ScipJava.class")));
            jar.closeEntry();
        }
        Path launcher = Files.write(directory.resolve(ScipJavaClasspath.LAUNCHER),
                ScipJavaClasspath.launcherBytes(List.of("scip-java-0.13.1.jar")));
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        List<String> command = new ArrayList<>(ScipJavaProcessPlanFactory.classpathCommand(
                java, launcher, temp.resolve("out").resolve("index.scip")));

        // The child JVM gets no inherited environment: JAVA_TOOL_OPTIONS on a runner would add a line to its output.
        Path stdout = temp.resolve("child.stdout");
        Path stderr = temp.resolve("child.stderr");
        ProcessBuilder builder = new ProcessBuilder(command).directory(temp.toFile())
                .redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
        builder.environment().clear();
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot != null) builder.environment().put("SystemRoot", systemRoot);
        Process process = builder.start();
        if (!process.waitFor(1, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new AssertionError("the launcher JVM did not finish");
        }

        assertEquals(0, process.exitValue(), Files.readString(stderr));
        assertEquals("scip-java index --output " + temp.resolve("out").resolve("index.scip").toAbsolutePath().normalize(),
                Files.readString(stdout).trim());
    }

    @Test
    void theClasspathCommandIsJavaJarTheLauncherThenTheIndexArguments() {
        Path java = temp.resolve("jdk").resolve("bin").resolve("java");
        Path launcher = temp.resolve("classpath").resolve(ScipJavaClasspath.LAUNCHER);
        Path output = temp.resolve("run").resolve("index.scip");

        assertEquals(List.of(java.toAbsolutePath().normalize().toString(), "-jar",
                        launcher.toAbsolutePath().normalize().toString(), "index", "--output",
                        output.toAbsolutePath().normalize().toString()),
                ScipJavaProcessPlanFactory.classpathCommand(java, launcher, output));
    }

    @Test
    void verificationHandsTheVerifierEveryJarBesideTheListingInOrder() throws IOException {
        Path listing = installed(NAMES);
        List<Path> seen = new ArrayList<>();

        Path launcher = ScipJavaClasspath.verify(listing, seen::addAll, true);

        assertEquals(List.of(listing.resolveSibling(NAMES.get(0)), listing.resolveSibling(NAMES.get(1))), seen);
        assertEquals(listing.resolveSibling(ScipJavaClasspath.LAUNCHER), launcher);
        assertNull(ScipJavaClasspath.verify(listing, ACCEPT, false), "the launcher is not looked at when not asked for");
    }

    @Test
    void aJarTheLockRefusesStopsTheVerification() throws IOException {
        Path listing = installed(NAMES);
        ScipJavaClasspath.Verifier refuse = jars -> {
            throw new IllegalStateException("scip-java classpath verification failed: scip-java-0.13.1.jar does not match its pinned SHA-256");
        };

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ScipJavaClasspath.verify(listing, refuse, true));

        assertTrue(failure.getMessage().endsWith("does not match its pinned SHA-256"), failure.getMessage());
    }

    @Test
    void aLauncherThatIsMissingAlteredOrOversizedIsRefusedWhenAskedFor() throws IOException {
        Path listing = installed(NAMES);
        Path launcher = listing.resolveSibling(ScipJavaClasspath.LAUNCHER);
        String expected = "scip-java classpath verification failed: scip-java-launcher.jar does not match the verified classpath";

        Files.write(launcher, ScipJavaClasspath.launcherBytes(List.of("scip-java-0.13.1.jar")));
        assertEquals(expected, assertThrows(IllegalStateException.class,
                () -> ScipJavaClasspath.verify(listing, ACCEPT, true)).getMessage());

        Files.delete(launcher);
        assertEquals(expected, assertThrows(IllegalStateException.class,
                () -> ScipJavaClasspath.verify(listing, ACCEPT, true)).getMessage());

        try (OutputStream output = Files.newOutputStream(launcher)) {
            output.write(new byte[256 * 1024]);
        }
        assertThrows(IOException.class, () -> ScipJavaClasspath.verify(listing, ACCEPT, true),
                "a launcher larger than any derived one is not read whole");
    }
}
