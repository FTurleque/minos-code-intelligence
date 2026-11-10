package com.minos.adapter.scip.runtime.install;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minos.adapter.scip.runtime.plan.ScipJavaClasspath;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The jars of the scip-java classpath, one name, size and SHA-256 each, as resolved when the catalogue was built.
 *
 * <p>An installation that resolves the classpath on the user's machine ({@code coursier fetch}) compares what it
 * got with this lock before it keeps anything: a jar substituted upstream or by a mirror, a jar added to the
 * dependency graph, a jar missing, all fail. Messages name the jar file only, never a directory.</p>
 *
 * <p>The lock may also force the version of a transitive dependency to correct an advisory (ADR SEC-0080): each
 * entry of {@code forcedVersions} names one {@code organisation:name} module, its version, the advisory and a
 * reason, and the jar of that version is pinned like any other. The resolution passes these versions to Coursier,
 * so the forcing and the verification read the same file. A malformed entry fails before anything is launched.</p>
 */
final class ClasspathLock implements ScipJavaClasspath.Verifier {

    static final String RESOURCE = EmbeddedToolsCatalog.RESOURCE_ROOT + "scip-java-classpath.lock.json";

    private static final Pattern MODULE = Pattern.compile("[^\\s:]+:[^\\s:]+");
    private static final Pattern VERSION = Pattern.compile("\\S+");

    private final String coordinate;
    private final Map<String, Entry> jars;
    private final List<ForcedVersion> forcedVersions;

    private record Entry(long size, String sha256) {
    }

    /** A module version forced in the Coursier resolution to correct an advisory (ADR SEC-0080). */
    record ForcedVersion(String module, String version) {

        /** The value of Coursier's {@code --force-version} option. */
        String argument() {
            return module + ":" + version;
        }
    }

    private ClasspathLock(String coordinate, Map<String, Entry> jars, List<ForcedVersion> forcedVersions) {
        this.coordinate = coordinate;
        this.jars = jars;
        this.forcedVersions = forcedVersions;
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
            String coordinate = root.required("coordinate").asText();
            return new ClasspathLock(coordinate, Map.copyOf(jars), forcedVersions(root.path("forcedVersions"), coordinate, jars.keySet()));
        } catch (RuntimeException malformed) {
            throw new IOException("the scip-java classpath lock is unreadable", malformed);
        }
    }

    /**
     * Every forced version is an object with an {@code organisation:name} module, a version, an advisory and a
     * reason; a module is forced once, never the provider itself, and the jar of its forced version is pinned.
     */
    private static List<ForcedVersion> forcedVersions(JsonNode node, String coordinate, Set<String> pinned) throws IOException {
        if (node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new IOException("the scip-java classpath lock is malformed");
        }
        String[] segments = coordinate.split(":", -1);
        String provider = segments.length > 1 ? segments[0] + ":" + segments[1] : coordinate;
        Set<String> seen = new HashSet<>();
        List<ForcedVersion> forced = new ArrayList<>();
        for (JsonNode entry : node) {
            String module = text(entry, "module");
            String version = text(entry, "version");
            if (!MODULE.matcher(module).matches() || !VERSION.matcher(version).matches()
                    || text(entry, "advisory").isBlank() || text(entry, "reason").isBlank()
                    || module.equals(provider) || !seen.add(module)
                    || !pinned.contains(module.substring(module.indexOf(':') + 1) + "-" + version + ".jar")) {
                throw new IOException("the scip-java classpath lock is malformed");
            }
            forced.add(new ForcedVersion(module, version));
        }
        return List.copyOf(forced);
    }

    private static String text(JsonNode entry, String field) {
        JsonNode value = entry.isObject() ? entry.get(field) : null;
        return value != null && value.isTextual() ? value.asText() : "";
    }

    String coordinate() {
        return coordinate;
    }

    /** The forced versions, in the order of the lock; empty when nothing is forced. */
    List<ForcedVersion> forcedVersions() {
        return forcedVersions;
    }

    Set<String> names() {
        return new TreeSet<>(jars.keySet());
    }

    /**
     * Compares the resolved jars with the lock, set to set: no jar missing, none extra, every size and SHA-256 equal.
     *
     * @throws IllegalStateException naming the first jar that differs (file name only)
     */
    @Override
    public void verify(List<Path> resolved) throws IOException {
        Objects.requireNonNull(resolved, "resolved");
        Map<String, Path> byName = indexByFileName(resolved);
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

    private static Map<String, Path> indexByFileName(List<Path> resolved) {
        Map<String, Path> byName = new LinkedHashMap<>();
        for (Path jar : resolved) {
            Path fileName = jar.getFileName();
            if (fileName == null) {
                throw new IllegalStateException("scip-java classpath verification failed: an entry has no file name");
            }
            String name = fileName.toString();
            if (byName.put(name, jar) != null) {
                throw new IllegalStateException("scip-java classpath verification failed: two jars are named " + name);
            }
        }
        return byName;
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
