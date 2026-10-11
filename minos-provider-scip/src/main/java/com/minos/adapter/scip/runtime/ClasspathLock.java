package com.minos.adapter.scip.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minos.io.ConfinedFileOpener;
import com.minos.io.Sha256;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * The jars of the scip-java classpath, one name, size and SHA-256 each, as resolved when the catalogue was built.
 *
 * <p>An installation that resolves the classpath on the user's machine ({@code coursier fetch}) compares what it
 * got with this lock before it keeps anything: a jar substituted upstream or by a mirror, a jar added to the
 * dependency graph, a jar missing, all fail. Messages name the jar file only, never a directory.</p>
 */
final class ClasspathLock {

    static final String RESOURCE = "scip-java-classpath.lock.json";

    private final String coordinate;
    private final Map<String, Entry> jars;

    private record Entry(long size, String sha256) {
    }

    private ClasspathLock(String coordinate, Map<String, Entry> jars) {
        this.coordinate = coordinate;
        this.jars = jars;
    }

    /** The lock packaged with MINOS. */
    static ClasspathLock packaged() throws IOException {
        try (InputStream input = ClasspathLock.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IOException("the packaged scip-java classpath lock is missing");
            }
            return parse(new InputStreamReader(input, StandardCharsets.UTF_8));
        }
    }

    static ClasspathLock parse(java.io.Reader reader) throws IOException {
        try {
            JsonNode root = new ObjectMapper().readTree(reader);
            Map<String, Entry> jars = new LinkedHashMap<>();
            for (JsonNode jar : root.required("jars")) {
                String name = jar.required("name").asText();
                String sha256 = jar.required("sha256").asText();
                if (!sha256.matches("[0-9a-f]{64}") || jars.put(name, new Entry(jar.required("size").asLong(), sha256)) != null) {
                    throw new IOException("the scip-java classpath lock is malformed");
                }
            }
            if (jars.isEmpty()) {
                throw new IOException("the scip-java classpath lock lists no jar");
            }
            return new ClasspathLock(root.required("coordinate").asText(), Map.copyOf(jars));
        } catch (RuntimeException malformed) {
            throw new IOException("the scip-java classpath lock is unreadable", malformed);
        }
    }

    String coordinate() {
        return coordinate;
    }

    Set<String> names() {
        return new TreeSet<>(jars.keySet());
    }

    /**
     * Compares the resolved jars with the lock, set to set: no jar missing, none extra, every size and SHA-256 equal.
     *
     * @throws IllegalStateException naming the first jar that differs (file name only)
     */
    void verify(List<Path> resolved) throws IOException {
        Objects.requireNonNull(resolved, "resolved");
        Map<String, Path> byName = new LinkedHashMap<>();
        for (Path jar : resolved) {
            String name = jar.getFileName().toString();
            if (byName.put(name, jar) != null) {
                throw new IllegalStateException("scip-java classpath verification failed: two jars are named " + name);
            }
        }
        for (String name : new TreeSet<>(byName.keySet())) {
            if (!jars.containsKey(name)) {
                throw new IllegalStateException("scip-java classpath verification failed: unexpected jar " + name);
            }
        }
        for (String name : new TreeSet<>(jars.keySet())) {
            Path jar = byName.get(name);
            if (jar == null) {
                throw new IllegalStateException("scip-java classpath verification failed: missing jar " + name);
            }
            Entry expected = jars.get(name);
            if (!Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("scip-java classpath verification failed: " + name + " is not a regular file");
            }
            if (Files.size(jar) != expected.size() || !expected.sha256().equals(sha256Of(jar))) {
                throw new IllegalStateException("scip-java classpath verification failed: " + name + " does not match its pinned SHA-256");
            }
        }
    }

    private static String sha256Of(Path file) throws IOException {
        MessageDigest digest = Sha256.newDigest();
        try (InputStream input = ConfinedFileOpener.openRegularFileNoFollow(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return Sha256.hex(digest);
    }
}
