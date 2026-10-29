package com.minos.adapter.scip.runtime.install;

import com.minos.adapter.scip.runtime.plan.ScipJavaClasspath;
import com.minos.io.BoundedInputStream;
import com.minos.io.BoundedLineReader;
import com.minos.io.ConfinedFileOpener;
import com.minos.io.PrivateLocalStorage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static com.minos.adapter.scip.runtime.install.ManagedToolInstallation.deleteRecursively;
import static com.minos.adapter.scip.runtime.install.ManagedToolInstallation.writeNewFile;

/**
 * The scip-java classpath: resolved by {@code coursier fetch}, verified against the pinned lock, copied into
 * MINOS_HOME/tools and swapped in, then verified again before each use.
 */
final class ScipJavaClasspathInstallation {

    /** The listing of the installed classpath, beside its jars. */
    static final String CLASSPATH_FILE = "classpath.txt";

    private static final int MAX_INSTALL_LOG_LINE_CHARS = 64 * 1024;
    // The largest jar of the lock is 54 MiB; a copy stops well before filling the disk.
    private static final long MAX_CLASSPATH_JAR_BYTES = 128L * 1024L * 1024L;

    private ScipJavaClasspathInstallation() {
    }

    /** Resolution only: scip-java is never launched, so nothing from the network runs before it is verified. */
    static List<String> scipJavaFetchCommand(Path coursier) {
        return List.of(coursier.toString(), "fetch", "--classpath", ManagedScipProviderRuntimeManager.SCIP_JAVA_COORDINATE);
    }

    /**
     * Resolves the scip-java classpath with {@code coursier fetch} and compares every jar with the pinned lock before
     * anything is kept. A mismatch (a jar replaced upstream or by a mirror, added, or missing) fails the installation.
     */
    static List<Path> fetchVerifiedClasspath(Path coursier, Path workingDirectory, Path log, ClasspathLock lock)
            throws IOException, InterruptedException {
        ManagedToolInstallation.run(scipJavaFetchCommand(coursier), workingDirectory, log, Duration.ofMinutes(10));
        List<Path> jars = resolvedJars(log);
        lock.verify(jars);
        return jars;
    }

    /** The classpath is the last line of the fetch output that names only absolute jar files. */
    static List<Path> resolvedJars(Path log) throws IOException {
        if (!Files.isRegularFile(log, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(log)) {
            throw new IOException("scip-java installation log must be a regular non-symbolic file");
        }
        List<Path> classpath = List.of();
        try (BoundedInputStream input = new BoundedInputStream(
                     ConfinedFileOpener.openRegularFileNoFollow(log), ManagedToolInstallation.MAX_INSTALL_LOG_BYTES,
                     "scip-java installation log");
             BoundedLineReader reader = new BoundedLineReader(
                     new InputStreamReader(input, StandardCharsets.UTF_8), MAX_INSTALL_LOG_LINE_CHARS)) {
            String line;
            while ((line = reader.readLine()) != null) {
                List<Path> candidate = jarList(line.trim());
                if (!candidate.isEmpty()) classpath = candidate;
            }
        }
        if (classpath.isEmpty()) throw new IllegalStateException("scip-java classpath resolution returned no jar");
        return classpath;
    }

    private static List<Path> jarList(String line) {
        if (line.isEmpty()) return List.of();
        List<Path> jars = new ArrayList<>();
        for (String entry : line.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            if (!entry.endsWith(".jar")) return List.of();
            Path jar;
            try {
                jar = Path.of(entry);
            } catch (java.nio.file.InvalidPathException _) {
                return List.of(); // a progress line such as "Downloaded https://.../x.jar" is not a classpath
            }
            if (!jar.isAbsolute()) return List.of();
            jars.add(jar);
        }
        return jars;
    }

    /**
     * Whether the installed classpath of {@code listing} is the verified one: its jars match the lock and, when
     * {@code verifyLauncher}, its launcher matches the listing. An absent, unreadable or altered tree (or one written
     * by an older MINOS, which listed the jars of the Coursier cache by absolute path) is installed again.
     */
    static boolean installedClasspathVerifies(Path listing, ClasspathLock lock, boolean verifyLauncher) {
        if (!Files.isRegularFile(listing, LinkOption.NOFOLLOW_LINKS)) return false;
        try {
            ScipJavaClasspath.verify(listing, lock, verifyLauncher);
            return true;
        } catch (IOException | RuntimeException _) {
            return false;
        }
    }

    /**
     * Copies the verified jars into {@code destination} (the index plan never reads the Coursier cache), verifies the
     * copies, writes the launcher and, last, the listing, then swaps the tree in. The layout is the one of the
     * Windows distribution: jars and listing side by side, the listing naming the jars relative to itself.
     *
     * <p>The files inherit the owner-only access of the private partial directory instead of carrying a protected
     * access list each, as the extracted Windows distribution does: the Windows sandbox grants its read access on the
     * directory, and a protected file would not inherit it. The previous tree is moved aside, not deleted, until the
     * new one is in place, so a failed swap restores it.</p>
     */
    static void installClasspath(Path destination, List<Path> jars, ClasspathLock lock) throws IOException {
        Path partial = destination.resolveSibling(destination.getFileName() + ".partial");
        Path previous = destination.resolveSibling(destination.getFileName() + ".previous");
        deleteRecursively(partial);
        PrivateLocalStorage.ensurePrivateDirectory(partial);
        try {
            List<String> names = new ArrayList<>(jars.size());
            List<Path> copies = new ArrayList<>(jars.size());
            for (Path jar : jars) {
                Path fileName = jar.getFileName();
                String name = ScipJavaClasspath.requireJarName(fileName == null ? null : fileName.toString());
                Path copy = partial.resolve(name);
                copyJar(jar, copy);
                names.add(name);
                copies.add(copy);
            }
            lock.verify(copies);
            writeNewFile(partial.resolve(ScipJavaClasspath.LAUNCHER),
                    new ByteArrayInputStream(ScipJavaClasspath.launcherBytes(names)));
            writeNewFile(partial.resolve(CLASSPATH_FILE), new ByteArrayInputStream(ScipJavaClasspath.listingBytes(names)));
            swapIn(partial, destination, previous);
        } catch (IOException | RuntimeException failure) {
            try {
                deleteRecursively(partial);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    /** Replaces {@code destination} by {@code partial}, keeping the previous tree aside until the swap is done. */
    static void swapIn(Path partial, Path destination, Path previous) throws IOException {
        swapIn(partial, destination, previous, ManagedToolInstallation::moveDirectory);
    }

    /** The publication of a directory, a seam of the tests of {@link #swapIn}. */
    @FunctionalInterface
    interface DirectoryMover {
        void move(Path source, Path target) throws IOException;
    }

    static void swapIn(Path partial, Path destination, Path previous, DirectoryMover mover) throws IOException {
        deleteRecursively(previous);
        boolean replacing = Files.exists(destination, LinkOption.NOFOLLOW_LINKS);
        if (replacing) mover.move(destination, previous);
        try {
            mover.move(partial, destination);
        } catch (IOException swapFailure) {
            if (replacing) restore(previous, destination, swapFailure, mover);
            throw swapFailure;
        }
        deleteRecursively(previous);
    }

    private static void restore(Path previous, Path destination, IOException swapFailure, DirectoryMover mover) {
        try {
            mover.move(previous, destination);
        } catch (IOException restoreFailure) {
            swapFailure.addSuppressed(restoreFailure);
        }
    }

    private static void copyJar(Path jar, Path copy) throws IOException {
        try (InputStream input = new BoundedInputStream(
                ConfinedFileOpener.openRegularFileNoFollow(jar), MAX_CLASSPATH_JAR_BYTES, "scip-java classpath jar")) {
            writeNewFile(copy, input);
        }
    }
}
