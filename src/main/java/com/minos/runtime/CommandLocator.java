package com.minos.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Résolution et invocation locale de commandes sans shell implicite. */
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

    /**
     * Construit une invocation ProcessBuilder portable. Sous Windows, les
     * launchers npm sont des .cmd/.bat et doivent passer explicitement par
     * cmd.exe ; les exécutables natifs restent lancés directement.
     */
    public static List<String> invocation(Path executable, String... arguments) {
        Path normalized = executable.toAbsolutePath().normalize();
        String fileName = normalized.getFileName().toString().toLowerCase(Locale.ROOT);
        List<String> values = new ArrayList<>();
        if (isWindows() && (fileName.endsWith(".cmd") || fileName.endsWith(".bat"))) {
            String commandProcessor = System.getenv("ComSpec");
            values.add(commandProcessor == null || commandProcessor.isBlank() ? "cmd.exe" : commandProcessor);
            values.add("/d");
            values.add("/c");
            values.add("call");
        }
        values.add(normalized.toString());
        values.addAll(Arrays.asList(arguments));
        return List.copyOf(values);
    }

    private static List<String> candidates(String command) {
        if (!isWindows() || command.contains(".")) {
            return List.of(command);
        }
        return List.of(command + ".exe", command + ".cmd", command + ".bat", command);
    }

    private static Optional<Path> executable(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        return Files.isRegularFile(normalized) ? Optional.of(normalized) : Optional.empty();
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
