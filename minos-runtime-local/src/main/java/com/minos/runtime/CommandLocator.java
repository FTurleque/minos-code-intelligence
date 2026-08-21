package com.minos.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** Résolution déterministe des exécutables externes sans passer par un shell implicite. */
public final class CommandLocator {

    private CommandLocator() {
    }

    public static Optional<Path> find(String command) {
        return findInPath(command, System.getenv("PATH"), isWindows());
    }

    /**
     * Searches only absolute PATH directories and returns the real path of the selected executable.
     *
     * <p>Empty PATH entries and relative entries such as {@code .} have current-directory semantics
     * on common process launchers. Accepting them would let a repository containing a planted
     * {@code docker.exe}, {@code npm.cmd}, {@code mvn.cmd}, etc. become process-launch authority merely
     * because MINOS was started from that directory. They are therefore ignored rather than
     * normalized against the current working directory.</p>
     */
    static Optional<Path> findInPath(String command, String pathValue, boolean windows) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("command must not be blank");
        }
        if (pathValue == null || pathValue.isBlank()) {
            return Optional.empty();
        }
        String separator = java.io.File.pathSeparator;
        for (String configured : pathValue.split(Pattern.quote(separator), -1)) {
            if (configured == null || configured.isBlank()) {
                continue;
            }
            Path directory;
            try {
                directory = Path.of(configured);
            } catch (InvalidPathException invalid) {
                continue;
            }
            if (!directory.isAbsolute()) {
                continue;
            }
            try {
                directory = directory.toRealPath();
            } catch (IOException | SecurityException unavailable) {
                continue;
            }
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            for (String candidate : candidates(command, windows)) {
                Path executable = directory.resolve(candidate).normalize();
                try {
                    Path real = executable.toRealPath();
                    if (Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
                        return Optional.of(real);
                    }
                } catch (IOException | SecurityException unavailable) {
                    // Keep searching the remaining trusted absolute PATH entries.
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
        try {
            Path root = Path.of(systemRoot);
            if (!root.isAbsolute()) return Optional.empty();
            return realRegularFile(root.resolve("System32").resolve("WindowsPowerShell")
                    .resolve("v1.0").resolve("powershell.exe"));
        } catch (InvalidPathException invalid) {
            return Optional.empty();
        }
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
        String commandProcessor = windowsCommandProcessor();
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

    private static String windowsCommandProcessor() {
        if (!isWindows()) {
            // Direct unit tests exercise the quoting renderer cross-platform; production reaches
            // this branch only on Windows through invocation().
            return "cmd.exe";
        }
        // ComSpec is intentionally not a trust source. It is inherited process environment and may
        // point at an arbitrary absolute executable. Batch execution is anchored instead to the
        // canonical Windows system directory; if that cannot be proven, fail closed.
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot != null && !systemRoot.isBlank()) {
            try {
                Path root = Path.of(systemRoot);
                if (root.isAbsolute()) {
                    Optional<Path> systemCmd = realRegularFile(root.resolve("System32").resolve("cmd.exe"));
                    if (systemCmd.isPresent()) return systemCmd.orElseThrow().toString();
                }
            } catch (InvalidPathException invalid) {
                // Fail below instead of falling back to ComSpec, PATH or a bare cmd.exe.
            }
        }
        throw new IllegalStateException("Windows command processor must resolve to SystemRoot\\System32\\cmd.exe");
    }

    private static Optional<Path> realRegularFile(Path path) {
        try {
            Path real = path.toRealPath();
            return Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)
                    ? Optional.of(real)
                    : Optional.empty();
        } catch (IOException | SecurityException unavailable) {
            return Optional.empty();
        }
    }

    private static boolean isBatch(Path executable) {
        String file = executable.getFileName().toString().toLowerCase(Locale.ROOT);
        return file.endsWith(".cmd") || file.endsWith(".bat");
    }

    private static List<String> candidates(String command, boolean windows) {
        String lower = command.toLowerCase(Locale.ROOT);
        if (!windows || lower.endsWith(".exe") || lower.endsWith(".cmd") || lower.endsWith(".bat")) {
            return List.of(command);
        }
        return List.of(command + ".exe", command + ".cmd", command + ".bat", command);
    }
}
