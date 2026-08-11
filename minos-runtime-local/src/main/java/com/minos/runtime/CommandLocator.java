package com.minos.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Résolution déterministe des exécutables externes sans passer par un shell implicite. */
public final class CommandLocator {

    private CommandLocator() {
    }

    public static Optional<Path> find(String command) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("command must not be blank");
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
                Path executable = Path.of(directory).resolve(candidate).toAbsolutePath().normalize();
                if (Files.isRegularFile(executable)) {
                    return Optional.of(executable);
                }
            }
        }
        return Optional.empty();
    }

    /** Resolves the Windows PowerShell 5.1 host even when its system directory is absent from PATH. */
    static Optional<Path> windowsPowerShell() {
        Optional<Path> fromPath = find("powershell");
        if (fromPath.isPresent() || !isWindows()) return fromPath;
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot == null || systemRoot.isBlank()) return Optional.empty();
        Path systemHost = Path.of(
                systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe")
                .toAbsolutePath().normalize();
        return Files.isRegularFile(systemHost) ? Optional.of(systemHost) : Optional.empty();
    }

    /**
     * Builds a direct process invocation. Windows batch files necessarily require cmd.exe.
     * The complete batch command is wrapped in cmd's required outer quote pair so /S cannot
     * strip the executable's own quotes and expose shell metacharacters from paths/arguments.
     * Percent expansion, embedded quotes and control newlines remain rejected fail-closed.
     */
    public static List<String> invocation(Path executable, String... arguments) {
        Objects.requireNonNull(executable, "executable");
        String[] values = arguments == null ? new String[0] : arguments;
        if (Arrays.stream(values).anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("arguments must not contain null values");
        }
        if (isWindows() && isBatch(executable)) {
            return windowsBatchInvocation(executable, values);
        }
        List<String> command = new ArrayList<>(values.length + 1);
        command.add(executable.toString());
        command.addAll(Arrays.asList(values));
        return List.copyOf(command);
    }

    static List<String> windowsBatchInvocation(Path executable, String... arguments) {
        Objects.requireNonNull(executable, "executable");
        String[] values = arguments == null ? new String[0] : arguments;
        if (Arrays.stream(values).anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("arguments must not contain null values");
        }
        StringBuilder rendered = new StringBuilder();
        appendBatchToken(rendered, executable.toString(), "executable");
        for (String argument : values) {
            rendered.append(' ');
            appendBatchToken(rendered, argument, "argument");
        }
        String comSpec = System.getenv("ComSpec");
        String commandProcessor = comSpec == null || comSpec.isBlank() ? "cmd.exe" : comSpec;
        // With cmd /S /C, the conventional and required shape is:
        //   ""C:\path with spaces\script.cmd" "arg" ..."
        // The outer pair belongs to cmd; the inner quotes protect every individual token.
        String commandLine = '"' + rendered.toString() + '"';
        return List.of(commandProcessor, "/d", "/v:off", "/s", "/c", commandLine);
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win");
    }

    private static void appendBatchToken(StringBuilder target, String value, String label) {
        if (value.indexOf('\0') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(label + " contains a forbidden control character");
        }
        if (value.indexOf('"') >= 0) {
            throw new IllegalArgumentException(label + " contains an embedded quote that cannot be represented safely by cmd.exe");
        }
        if (value.indexOf('%') >= 0) {
            throw new IllegalArgumentException(label + " contains '%' and would enable cmd.exe environment expansion");
        }
        target.append('"').append(value).append('"');
    }

    private static boolean isBatch(Path executable) {
        String file = executable.getFileName().toString().toLowerCase(Locale.ROOT);
        return file.endsWith(".cmd") || file.endsWith(".bat");
    }

    private static List<String> candidates(String command) {
        String lower = command.toLowerCase(Locale.ROOT);
        if (!isWindows() || lower.endsWith(".exe") || lower.endsWith(".cmd") || lower.endsWith(".bat")) {
            return List.of(command);
        }
        return List.of(command + ".exe", command + ".cmd", command + ".bat", command);
    }
}
