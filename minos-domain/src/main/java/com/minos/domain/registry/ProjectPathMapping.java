package com.minos.domain.registry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * Typed host/container project-root mapping. Business persistence stores only a relative project root;
 * this mapping resolves that root to the physical location visible in the current runtime.
 *
 * <p>Two forms. A <em>single root</em> mapping ({@link #singleRoot}) maps one host folder onto the container root.
 * A <em>mount table</em> ({@link #mounted}) has no host root: the container root holds one read-only mount per exposed
 * project, and a relative root resolves on the host only through the mount whose path prefixes it. A table refuses
 * overlapping mount paths, a host folder equal to or containing another, and more than {@link #MAX_MOUNTS}
 * mounts.</p>
 *
 * @param hostRoot the host root of a single root mapping, or {@code null} for a mount table
 * @param containerRoot the absolute container project root
 * @param mounts the mounts of a mount table, empty for a single root mapping
 */
public record ProjectPathMapping(String hostRoot, String containerRoot, List<ProjectMount> mounts) {

    public static final String RUNTIME_LOCATION_ENV = "MINOS_RUNTIME_LOCATION";
    public static final String RUNTIME_LOCATION_PROPERTY = "minos.runtime.location";

    /** The largest number of project mounts a table holds. */
    public static final int MAX_MOUNTS = 64;

    private static final Pattern MOUNT_NAME = Pattern.compile("[a-z0-9][a-z0-9._-]{0,62}");

    public enum RuntimeLocation {
        NATIVE,
        DOCKER;

        static RuntimeLocation parse(String value) {
            if (value == null || value.isBlank()) return NATIVE;
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "native" -> NATIVE;
                case "docker" -> DOCKER;
                default -> throw new IllegalArgumentException("unsupported MINOS runtime location: " + value);
            };
        }
    }

    public ProjectPathMapping {
        containerRoot = normalizeAbsoluteRoot(containerRoot, "containerRoot");
        mounts = List.copyOf(Objects.requireNonNull(mounts, "mounts"));
        if (hostRoot != null) {
            hostRoot = normalizeAbsoluteRoot(hostRoot, "hostRoot");
            if (!mounts.isEmpty()) throw new IllegalArgumentException("a single root mapping has no mounts");
        } else {
            validateTable(mounts);
        }
    }

    /** The mapping of one host root onto the container root. */
    public static ProjectPathMapping singleRoot(String hostRoot, String containerRoot) {
        if (hostRoot == null) throw new IllegalArgumentException("hostRoot must not be blank");
        return new ProjectPathMapping(hostRoot, containerRoot, List.of());
    }

    /** The mount table of {@code containerRoot}; an empty table exposes no project. */
    public static ProjectPathMapping mounted(String containerRoot, List<ProjectMount> mounts) {
        return new ProjectPathMapping(null, containerRoot, mounts);
    }

    public static RuntimeLocation resolveRuntimeLocation(Map<String, String> environment, Properties properties) {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(properties, "properties");
        String property = properties.getProperty(RUNTIME_LOCATION_PROPERTY);
        String configured = property == null || property.isBlank()
                ? environment.get(RUNTIME_LOCATION_ENV)
                : property;
        return RuntimeLocation.parse(configured);
    }

    /** True for a mount table, which has no host root. */
    public boolean mountTable() {
        return hostRoot == null;
    }

    /** True when {@code name} is a valid name for a new mount: {@code [a-z0-9][a-z0-9._-]{0,62}}. */
    public static boolean isValidMountName(String name) {
        return name != null && MOUNT_NAME.matcher(name).matches();
    }

    /**
     * The default mount name of a host folder: its last segment in lower case, every character outside the name
     * alphabet replaced by {@code -}; empty when the result is not a valid name.
     */
    public static Optional<String> defaultMountName(String hostFolder) {
        String normalized = normalizeCandidate(hostFolder);
        String leaf = normalized.substring(normalized.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        StringBuilder name = new StringBuilder(leaf.length());
        for (int index = 0; index < leaf.length(); index++) {
            char character = leaf.charAt(index);
            boolean allowed = (character >= 'a' && character <= 'z') || (character >= '0' && character <= '9')
                    || character == '.' || character == '_' || character == '-';
            name.append(allowed ? character : '-');
        }
        String candidate = name.toString();
        return isValidMountName(candidate) && !".".equals(candidate) && !"..".equals(candidate)
                ? Optional.of(candidate)
                : Optional.empty();
    }

    /** This table with a new mount {@code name} of {@code hostFolder}; refused when invalid, taken or overlapping. */
    public ProjectPathMapping withMount(String name, String hostFolder) {
        requireTable();
        if (!isValidMountName(name)) {
            throw new IllegalArgumentException("invalid project mount name; use [a-z0-9][a-z0-9._-]{0,62}");
        }
        if (mounts.size() >= MAX_MOUNTS) {
            throw new IllegalArgumentException("the project mount table is full: at most " + MAX_MOUNTS + " mounts");
        }
        List<ProjectMount> next = new ArrayList<>(mounts);
        next.add(new ProjectMount(name, hostFolder));
        return mounted(containerRoot, next);
    }

    /** This table without the mount {@code name}; refused when no mount has that name. */
    public ProjectPathMapping withoutMount(String name) {
        requireTable();
        List<ProjectMount> next = new ArrayList<>(mounts);
        if (!next.removeIf(mount -> mount.path().equals(name))) {
            throw new IllegalArgumentException("unknown project mount: " + name);
        }
        return mounted(containerRoot, next);
    }

    public String relativeForPhysical(Path physicalPath, RuntimeLocation runtimeLocation) {
        Objects.requireNonNull(physicalPath, "physicalPath");
        Objects.requireNonNull(runtimeLocation, "runtimeLocation");
        String candidate = normalizeCandidate(physicalPath.toAbsolutePath().normalize().toString());
        if (runtimeLocation == RuntimeLocation.NATIVE && mountTable()) {
            return relativeForHostPath(candidate).orElseThrow(
                    () -> new IllegalArgumentException("project path is outside every project mount: " + candidate));
        }
        return relativeFrom(rootFor(runtimeLocation), candidate);
    }

    /** Resolves an absolute legacy path, persisted by older registries, against either known root. */
    public String relativeForLegacyPath(String legacyPath) {
        String normalized = normalizeCandidate(legacyPath);
        IllegalArgumentException hostFailure;
        try {
            return mountTable()
                    ? relativeForHostPath(normalized).orElseThrow(
                            () -> new IllegalArgumentException("legacy path is outside every project mount"))
                    : relativeFrom(hostRoot, normalized);
        } catch (IllegalArgumentException exception) {
            hostFailure = exception;
        }
        try {
            return relativeFrom(containerRoot, normalized);
        } catch (IllegalArgumentException exception) {
            exception.addSuppressed(hostFailure);
            throw new IllegalArgumentException(
                    "legacy project path is outside both configured roots: " + legacyPath, exception);
        }
    }

    public Path resolve(String relativeProjectRoot, RuntimeLocation runtimeLocation) {
        Objects.requireNonNull(runtimeLocation, "runtimeLocation");
        String relative = normalizeRelative(relativeProjectRoot);
        if (runtimeLocation == RuntimeLocation.NATIVE && mountTable()) {
            return Path.of(hostPathOf(relative)).toAbsolutePath().normalize();
        }
        Path resolved = Path.of(rootFor(runtimeLocation));
        if (!".".equals(relative)) {
            for (String segment : relative.split("/")) {
                resolved = resolved.resolve(segment);
            }
        }
        return resolved.toAbsolutePath().normalize();
    }

    /**
     * The absolute host folder of {@code relativeProjectRoot}: joined to the host root of a single root mapping, or
     * resolved through the mount whose path prefixes it. Empty when no mount exposes it.
     */
    public Optional<String> hostPathFor(String relativeProjectRoot) {
        String relative = normalizeRelative(relativeProjectRoot);
        if (!mountTable()) return Optional.of(join(hostRoot, relative));
        for (ProjectMount mount : mounts) {
            Optional<String> rest = remainder(mount.path(), relative, true);
            if (rest.isPresent()) return Optional.of(join(mount.hostRoot(), rest.orElseThrow()));
        }
        return Optional.empty();
    }

    /** The absolute host path of {@code relativeProjectRoot}, as a string: valid in any runtime, never resolved. */
    public String hostPathOf(String relativeProjectRoot) {
        return hostPathFor(relativeProjectRoot).orElseThrow(
                () -> new IllegalArgumentException("project root is not exposed by any project mount"));
    }

    /** The root of {@code hostPath} relative to the container root, or empty when no root or mount holds it. */
    public Optional<String> relativeForHostPath(String hostPath) {
        String candidate = normalizeCandidate(hostPath);
        if (mountTable()) {
            for (ProjectMount mount : mounts) {
                Optional<String> rest = remainder(mount.hostRoot(), candidate, isWindowsAbsolute(mount.hostRoot()));
                if (rest.isPresent()) return Optional.of(join(mount.path(), rest.orElseThrow()));
            }
            return Optional.empty();
        }
        try {
            return Optional.of(relativeFrom(hostRoot, candidate));
        } catch (IllegalArgumentException _) {
            return Optional.empty();
        }
    }

    public String hostToContainer(String hostPath) {
        String relative = relativeForHostPath(hostPath).orElseThrow(
                () -> new IllegalArgumentException("project path is outside configured root: " + hostPath));
        return join(containerRoot, relative);
    }

    public String containerToHost(String containerPath) {
        return hostPathOf(relativeFrom(containerRoot, normalizeCandidate(containerPath)));
    }

    private String rootFor(RuntimeLocation runtimeLocation) {
        return runtimeLocation == RuntimeLocation.DOCKER ? containerRoot : hostRoot;
    }

    private void requireTable() {
        if (!mountTable()) throw new IllegalStateException("a single root mapping has no project mounts");
    }

    private static void validateTable(List<ProjectMount> mounts) {
        if (mounts.size() > MAX_MOUNTS) {
            throw new IllegalArgumentException("the project mount table is full: at most " + MAX_MOUNTS + " mounts");
        }
        for (int first = 0; first < mounts.size(); first++) {
            for (int second = first + 1; second < mounts.size(); second++) {
                ProjectMount one = mounts.get(first);
                ProjectMount other = mounts.get(second);
                if (overlaps(one.path(), other.path(), true)) {
                    throw new IllegalArgumentException("project mount paths overlap: " + one.path() + ", " + other.path());
                }
                boolean caseInsensitive = isWindowsAbsolute(one.hostRoot()) || isWindowsAbsolute(other.hostRoot());
                if (overlaps(one.hostRoot(), other.hostRoot(), caseInsensitive)) {
                    throw new IllegalArgumentException("project mount host folders overlap: "
                            + one.path() + ", " + other.path());
                }
            }
        }
    }

    /** True when one path is equal to the other or contains it. */
    private static boolean overlaps(String one, String other, boolean caseInsensitive) {
        return remainder(one, other, caseInsensitive).isPresent() || remainder(other, one, caseInsensitive).isPresent();
    }

    /** What follows {@code prefix} in {@code candidate}, {@code "."} when equal, or empty when it is not a prefix. */
    private static Optional<String> remainder(String prefix, String candidate, boolean caseInsensitive) {
        String comparablePrefix = caseInsensitive ? prefix.toLowerCase(Locale.ROOT) : prefix;
        String comparableCandidate = caseInsensitive ? candidate.toLowerCase(Locale.ROOT) : candidate;
        if (comparableCandidate.equals(comparablePrefix)) return Optional.of(".");
        if (!comparableCandidate.startsWith(comparablePrefix + "/")) return Optional.empty();
        return Optional.of(candidate.substring(prefix.length() + 1));
    }

    private static String relativeFrom(String root, String candidate) {
        boolean caseInsensitive = isWindowsAbsolute(root);
        String comparableRoot = caseInsensitive ? root.toLowerCase(Locale.ROOT) : root;
        String comparableCandidate = caseInsensitive ? candidate.toLowerCase(Locale.ROOT) : candidate;
        if (comparableCandidate.equals(comparableRoot)) return ".";
        String prefix = comparableRoot + "/";
        if (!comparableCandidate.startsWith(prefix)) {
            throw new IllegalArgumentException("project path is outside configured root: " + candidate);
        }
        return normalizeRelative(candidate.substring(root.length() + 1));
    }

    private static String join(String root, String relative) {
        return ".".equals(relative) ? root : root + "/" + relative;
    }

    static String normalizeAbsoluteRoot(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " must not be blank");
        String normalized = normalizeCandidate(value);
        if (!isWindowsAbsolute(normalized) && !normalized.startsWith("/")) {
            throw new IllegalArgumentException(label + " must be an absolute Windows or POSIX path: " + value);
        }
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String normalizeCandidate(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("path must not be blank");
        String normalized = value.trim().replace('\\', '/');
        while (normalized.contains("//")) normalized = normalized.replace("//", "/");
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    static String normalizeRelative(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("relative project root must not be blank");
        String normalized = normalizeCandidate(value);
        if (".".equals(normalized)) return normalized;
        if (normalized.startsWith("/") || isWindowsAbsolute(normalized)) {
            throw new IllegalArgumentException("project root must be relative: " + value);
        }
        for (String segment : normalized.split("/")) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("unsafe relative project root: " + value);
            }
        }
        return normalized;
    }

    private static boolean isWindowsAbsolute(String value) {
        return value.length() >= 3
                && Character.isLetter(value.charAt(0))
                && value.charAt(1) == ':'
                && value.charAt(2) == '/';
    }
}
