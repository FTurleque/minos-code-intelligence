package com.minos.registry;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * Typed host/container project-root mapping. Business persistence stores only a relative project root;
 * this mapping resolves that root to the physical location visible in the current runtime.
 */
public record ProjectPathMapping(String hostRoot, String containerRoot) {

    public static final String RUNTIME_LOCATION_ENV = "MINOS_RUNTIME_LOCATION";
    public static final String RUNTIME_LOCATION_PROPERTY = "minos.runtime.location";

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
        hostRoot = normalizeAbsoluteRoot(hostRoot, "hostRoot");
        containerRoot = normalizeAbsoluteRoot(containerRoot, "containerRoot");
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

    public String relativeForPhysical(Path physicalPath, RuntimeLocation runtimeLocation) {
        Objects.requireNonNull(physicalPath, "physicalPath");
        Objects.requireNonNull(runtimeLocation, "runtimeLocation");
        return relativeFrom(rootFor(runtimeLocation), normalizeCandidate(physicalPath.toAbsolutePath().normalize().toString()));
    }

    /** Resolves an absolute legacy path persisted by pre-M29 registries against either known root. */
    public String relativeForLegacyPath(String legacyPath) {
        String normalized = normalizeCandidate(legacyPath);
        IllegalArgumentException hostFailure;
        try {
            return relativeFrom(hostRoot, normalized);
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
        Path resolved = Path.of(rootFor(runtimeLocation));
        if (!".".equals(relative)) {
            for (String segment : relative.split("/")) {
                resolved = resolved.resolve(segment);
            }
        }
        return resolved.toAbsolutePath().normalize();
    }

    public String hostToContainer(String hostPath) {
        return join(containerRoot, relativeFrom(hostRoot, normalizeCandidate(hostPath)));
    }

    public String containerToHost(String containerPath) {
        return join(hostRoot, relativeFrom(containerRoot, normalizeCandidate(containerPath)));
    }

    private String rootFor(RuntimeLocation runtimeLocation) {
        return runtimeLocation == RuntimeLocation.DOCKER ? containerRoot : hostRoot;
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

    private static String normalizeAbsoluteRoot(String value, String label) {
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

    private static String normalizeRelative(String value) {
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
