package com.minos.intellij.protocol;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Resolves the MINOS launcher without ever consulting the active project working directory. */
final class MinosExecutableResolver {

    private static final String DEFAULT_WINDOWS_PATHEXT = ".COM;.EXE;.BAT;.CMD";

    private MinosExecutableResolver() {
    }

    static Path resolve(String configured, String osName) throws IOException {
        return resolve(configured, osName, System.getenv());
    }

    static Path resolve(String configured, String osName, Map<String, String> environment) throws IOException {
        String value = requireExecutable(configured);
        boolean windows = osName != null && osName.toLowerCase(Locale.ROOT).contains("win");
        Path parsed;
        try {
            parsed = Path.of(value);
        } catch (RuntimeException invalid) {
            throw new IOException("invalid MINOS executable path: " + value, invalid);
        }
        if (parsed.isAbsolute()) {
            return requireRegularExecutable(parsed, value);
        }
        if (containsDirectorySyntax(value, windows)) {
            throw new IOException(
                    "relative MINOS executable paths are forbidden; configure an absolute path or a PATH command: "
                            + value);
        }

        Map<String, String> env = Objects.requireNonNull(environment, "environment");
        String pathValue = environmentValue(env, "PATH", windows);
        if (pathValue == null || pathValue.isBlank()) {
            throw new IOException("PATH is unavailable while resolving MINOS executable: " + value);
        }
        List<String> names = executableNames(value, windows, environmentValue(env, "PATHEXT", windows));
        String separator = windows ? ";" : java.io.File.pathSeparator;
        for (String rawDirectory : pathValue.split(java.util.regex.Pattern.quote(separator), -1)) {
            String cleaned = stripOptionalQuotes(rawDirectory.trim());
            if (cleaned.isBlank()) {
                // Empty PATH elements mean the current working directory on common shells. Never
                // honor that semantic because the client deliberately works inside the project.
                continue;
            }
            Path directory;
            try {
                directory = Path.of(cleaned);
            } catch (RuntimeException invalid) {
                continue;
            }
            if (!directory.isAbsolute()) {
                // Relative PATH elements are equally project-sensitive after ProcessBuilder.directory().
                continue;
            }
            for (String name : names) {
                Path candidate = directory.resolve(name).normalize();
                Path resolved = regularExecutableOrNull(candidate);
                if (resolved != null) return resolved;
            }
        }
        throw new IOException("cannot resolve MINOS executable from absolute PATH entries: " + value);
    }

    private static String requireExecutable(String configured) throws IOException {
        if (configured == null || configured.isBlank()) {
            throw new IOException("MINOS executable must not be blank");
        }
        String value = configured.trim();
        if (value.indexOf('\0') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IOException("MINOS executable contains a forbidden control character");
        }
        return value;
    }

    private static boolean containsDirectorySyntax(String value, boolean windows) {
        if (value.indexOf('/') >= 0 || value.indexOf('\\') >= 0) return true;
        return windows && value.indexOf(':') >= 0;
    }

    private static List<String> executableNames(String value, boolean windows, String pathExt) {
        if (!windows || hasExtension(value)) return List.of(value);
        String effective = pathExt == null || pathExt.isBlank() ? DEFAULT_WINDOWS_PATHEXT : pathExt;
        List<String> names = new ArrayList<>();
        for (String extension : effective.split(";")) {
            String normalized = extension.trim();
            if (normalized.isEmpty()) continue;
            if (!normalized.startsWith(".")) normalized = "." + normalized;
            names.add(value + normalized.toLowerCase(Locale.ROOT));
            names.add(value + normalized.toUpperCase(Locale.ROOT));
        }
        return names.isEmpty() ? List.of(value) : List.copyOf(names);
    }

    private static boolean hasExtension(String value) {
        int dot = value.lastIndexOf('.');
        return dot > 0 && dot < value.length() - 1;
    }

    private static String environmentValue(Map<String, String> environment, String key, boolean windows) {
        if (!windows) return environment.get(key);
        return environment.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(key))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    private static String stripOptionalQuotes(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static Path requireRegularExecutable(Path candidate, String configured) throws IOException {
        Path resolved = regularExecutableOrNull(candidate);
        if (resolved == null) {
            throw new IOException("configured MINOS executable is not a regular file: " + configured);
        }
        return resolved;
    }

    private static Path regularExecutableOrNull(Path candidate) {
        try {
            Path real = candidate.toRealPath();
            if (!Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) return null;
            return real.toAbsolutePath().normalize();
        } catch (IOException | SecurityException failure) {
            return null;
        }
    }
}
