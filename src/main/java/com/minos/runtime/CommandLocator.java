package com.minos.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Résolution locale de commandes sans shell. */
public final class CommandLocator {

    private CommandLocator() {
    }

    public static Optional<Path> find(String command) {
        if (command == null || command.isBlank()) {
            return Optional.empty();
        }
        Path direct = Path.of(command);
        if (direct.getNameCount() > 1 || direct.isAbsolute()) {
            return executable(direct);
        }
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        for (String directory : path.split(java.io.File.pathSeparator)) {
            if (directory == null || directory.isBlank()) {
                continue;
            }
            for (String candidate : candidates(command)) {
                Optional<Path> found = executable(Path.of(directory).resolve(candidate));
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        return Optional.empty();
    }

    private static List<String> candidates(String command) {
        if (!isWindows() || command.contains(".")) {
            return List.of(command);
        }
        List<String> values = new ArrayList<>();
        values.add(command + ".exe");
        values.add(command + ".cmd");
        values.add(command + ".bat");
        values.add(command);
        return List.copyOf(values);
    }

    private static Optional<Path> executable(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        return Files.isRegularFile(normalized) ? Optional.of(normalized) : Optional.empty();
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
