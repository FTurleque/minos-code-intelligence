package com.minos.adapter.scip.runtime.plan;

import com.minos.io.BoundedInputStream;
import com.minos.io.BoundedLineReader;
import com.minos.io.ConfinedFileOpener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The scip-java classpath kept in {@code MINOS_HOME/tools}: the jars, a listing ({@value #LISTING}, one jar file name
 * per line, relative to the listing, the layout of the Windows distribution) and a launcher jar ({@value #LAUNCHER})
 * whose manifest names the jars and the main class of scip-java.
 *
 * <p>The installation writes them after comparing every jar with the pinned lock. The index plan trusts none of it:
 * it compares the jars with the lock again and the launcher with the bytes this class derives from the listing, so a
 * jar replaced after the installation is refused instead of executed. Nothing is ever resolved by coordinate at index
 * time. Messages name a jar file at most, never a directory.</p>
 */
public final class ScipJavaClasspath {

    /** The listing of the jars, written last by the installation: its presence means the jars were verified. */
    public static final String LISTING = "classpath.txt";
    /** Manifest-only jar that names the jars ({@code Class-Path}) and the main class: {@code java -jar} runs it. */
    public static final String LAUNCHER = "scip-java-launcher.jar";
    static final String MAIN_CLASS = "org.scip_code.scip_java.ScipJava";

    // A plain file name: no separator, so an entry cannot leave the classpath directory, and nothing a manifest
    // Class-Path (a list of relative URLs separated by spaces) would have to escape.
    private static final Pattern JAR_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]*\\.jar");
    private static final int MAX_LISTING_BYTES = 64 * 1024;
    private static final int MAX_LISTING_LINE_CHARS = 256;
    // Fixed and inside the DOS date range: the launcher has the same bytes whenever and wherever it is derived.
    private static final LocalDateTime ENTRY_TIME = LocalDateTime.of(1980, 2, 1, 0, 0);

    /** Compares jars with the pinned lock, set to set; fails naming the first jar that differs. */
    @FunctionalInterface
    public interface Verifier {
        void verify(List<Path> jars) throws IOException;
    }

    private ScipJavaClasspath() {
    }

    /**
     * @return {@code name} when it is a plain jar file name
     * @throws IllegalStateException otherwise, without repeating the name (it may be a path)
     */
    public static String requireJarName(String name) {
        if (name == null || !JAR_NAME.matcher(name).matches()) {
            throw new IllegalStateException("the managed scip-java classpath names something that is not a jar file name");
        }
        return name;
    }

    /** The listing of {@code names}, one per line. */
    public static byte[] listing(List<String> names) {
        StringBuilder text = new StringBuilder();
        for (String name : requireNames(names)) text.append(name).append('\n');
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** The launcher of {@code names}: a stored (uncompressed) jar holding only its manifest, the same bytes every time. */
    public static byte[] launcher(List<String> names) {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.put(Attributes.Name.MAIN_CLASS, MAIN_CLASS);
        attributes.put(Attributes.Name.CLASS_PATH, String.join(" ", requireNames(names)));
        try {
            ByteArrayOutputStream manifestBytes = new ByteArrayOutputStream();
            manifest.write(manifestBytes);
            byte[] content = manifestBytes.toByteArray();
            CRC32 crc = new CRC32();
            crc.update(content);
            ZipEntry entry = new ZipEntry(JarFile.MANIFEST_NAME);
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(content.length);
            entry.setCompressedSize(content.length);
            entry.setCrc(crc.getValue());
            entry.setTimeLocal(ENTRY_TIME);
            ByteArrayOutputStream jar = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(jar)) {
                zip.putNextEntry(entry);
                zip.write(content);
                zip.closeEntry();
            }
            return jar.toByteArray();
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible); // in-memory streams do not fail
        }
    }

    /**
     * Reads the jar names of a listing, bounded, without following a link.
     *
     * @throws IllegalStateException when the listing is empty, names something other than a jar file name, or names
     *     a jar twice
     */
    public static List<String> readListing(Path listing) throws IOException {
        Objects.requireNonNull(listing, "listing");
        List<String> names = new ArrayList<>();
        try (BoundedInputStream input = new BoundedInputStream(
                     ConfinedFileOpener.openRegularFileNoFollow(listing), MAX_LISTING_BYTES, "scip-java classpath listing");
             BoundedLineReader reader = new BoundedLineReader(
                     new InputStreamReader(input, StandardCharsets.UTF_8), MAX_LISTING_LINE_CHARS)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String name = line.trim();
                if (!name.isEmpty()) names.add(name);
            }
        }
        return requireNames(names);
    }

    /**
     * Verifies the installed classpath described by {@code listing}: every jar it names, beside it, against the lock;
     * and, when {@code withLauncher}, the launcher beside it against the bytes derived from the listing.
     *
     * @return the launcher, or {@code null} when it was not asked for
     * @throws IllegalStateException when a jar or the launcher differs from what was verified at installation
     */
    public static Path verify(Path listing, Verifier verifier, boolean withLauncher) throws IOException {
        Objects.requireNonNull(verifier, "verifier");
        Path directory = Objects.requireNonNull(listing, "listing").toAbsolutePath().normalize().getParent();
        List<String> names = readListing(listing);
        List<Path> jars = new ArrayList<>(names.size());
        for (String name : names) jars.add(directory.resolve(name));
        verifier.verify(jars);
        if (!withLauncher) return null;
        Path launcher = directory.resolve(LAUNCHER);
        if (!Files.isRegularFile(launcher, LinkOption.NOFOLLOW_LINKS)
                || !Arrays.equals(launcher(names), readLauncher(launcher))) {
            throw new IllegalStateException("scip-java classpath verification failed: " + LAUNCHER
                    + " does not match the verified classpath");
        }
        return launcher;
    }

    private static byte[] readLauncher(Path launcher) throws IOException {
        // The launcher of a listing within its bound is smaller than this: a larger file is not one this class wrote.
        try (InputStream input = new BoundedInputStream(
                ConfinedFileOpener.openRegularFileNoFollow(launcher), MAX_LISTING_BYTES * 2L, "scip-java launcher")) {
            return input.readAllBytes();
        }
    }

    private static List<String> requireNames(List<String> names) {
        Objects.requireNonNull(names, "names");
        if (names.isEmpty()) {
            throw new IllegalStateException("the managed scip-java classpath names no jar");
        }
        Set<String> seen = new HashSet<>();
        for (String name : names) {
            if (!seen.add(requireJarName(name))) {
                throw new IllegalStateException("the managed scip-java classpath names " + name + " twice");
            }
        }
        return List.copyOf(names);
    }
}
