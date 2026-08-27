package com.minos.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Résolution déterministe des exécutables externes sans passer par un shell implicite. */
public final class CommandLocator {

    private static final Set<String> LINUX_SECURITY_AUTHORITY_COMMANDS = Set.of("bwrap", "prlimit", "sh");
    private static final List<Path> LINUX_SYSTEM_DIRECTORIES = List.of(
            Path.of("/usr/bin"), Path.of("/bin"), Path.of("/usr/sbin"), Path.of("/sbin"));

    private CommandLocator() {
    }

    public static Optional<Path> find(String command) {
        String normalized = requireCommand(command).toLowerCase(Locale.ROOT);
        if (isLinux() && LINUX_SECURITY_AUTHORITY_COMMANDS.contains(normalized)) {
            return findSystemExecutable(command);
        }
        return findInPath(command, System.getenv("PATH"), isWindows());
    }

    /**
     * Resolves a Linux security-authority executable only from root-owned, non-group/world-writable
     * system directories. The real executable must stay inside the real system directory and must
     * itself be root-owned and non-group/world-writable.
     *
     * <p>This intentionally does not consult PATH. It is for commands that <em>create or enter the
     * security boundary itself</em> (currently bubblewrap, prlimit and the cgroup launcher shell),
     * not for operator-selected provider runtimes such as npm, dotnet or docker.</p>
     */
    static Optional<Path> findSystemExecutable(String command) {
        requireCommand(command);
        if (!isLinux()) return Optional.empty();
        for (Path configuredDirectory : LINUX_SYSTEM_DIRECTORIES) {
            try {
                Path directory = configuredDirectory.toRealPath();
                if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                        || !isRootOwnedAndNotGroupWorldWritable(directory)) {
                    continue;
                }
                Path candidate = directory.resolve(command).normalize();
                Path real = candidate.toRealPath();
                if (!real.startsWith(directory)
                        || !Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)
                        || !Files.isExecutable(real)
                        || !isRootOwnedAndNotGroupWorldWritable(real)) {
                    continue;
                }
                return Optional.of(real);
            } catch (IOException | SecurityException | UnsupportedOperationException unavailable) {
                // Continue through the remaining fixed system roots; never fall back to PATH.
            }
        }
        return Optional.empty();
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
        requireCommand(command);
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
                    // Keep searching the remaining absolute PATH entries.
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Resolves the Windows PowerShell 5.1 host from the canonical system directory only.
     * PATH is intentionally not a trust source because this executable creates Job Object and
     * AppContainer security boundaries.
     */
    static Optional<Path> windowsPowerShell() {
        if (!isWindows()) return find("powershell");
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot == null || systemRoot.isBlank()) return Optional.empty();
        try {
            Path root = Path.of(systemRoot);
            if (!root.isAbsolute()) return Optional.empty();
            Path realRoot = root.toRealPath();
            Optional<Path> powershell = realRegularFile(root.resolve("System32").resolve("WindowsPowerShell")
                    .resolve("v1.0").resolve("powershell.exe"));
            if (powershell.isEmpty() || !powershell.orElseThrow().startsWith(realRoot)) return Optional.empty();
            return powershell;
        } catch (InvalidPathException | IOException | SecurityException invalid) {
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
        //   ""C:\\path with spaces\\script.cmd" "arg" ..."
        // The outer pair belongs to cmd; the inner quotes protect every individual token.
        String commandLine = '"' + rendered.toString() + '"';
        return List.of(commandProcessor, "/d", "/v:off", "/s", "/c", commandLine);
    }

    /**
     * If {@code command} matches the exact shape {@link #windowsBatchInvocation} produces, returns
     * the original batch executable its one pre-quoted argument encodes. A sandbox granting access
     * by inspecting {@code command} would otherwise never see that path at all: cmd.exe's own /S /C
     * contract requires it hidden inside a single argument, so nothing about the command's other
     * elements names it directly. Empty for any other command shape, including a plain executable.
     *
     * <p>Extraction is unambiguous, not a heuristic: {@link #appendBatchToken} rejects any token
     * containing an embedded quote, so the first quoted token in the rendered line -- delimited by
     * the very next {@code "} after the two leading quote characters -- can only be the executable
     * {@link #windowsBatchInvocation} itself appended first.</p>
     */
    static Optional<Path> windowsBatchExecutable(List<String> command) {
        if (command.size() != 6
                || !"/d".equalsIgnoreCase(command.get(1))
                || !"/v:off".equalsIgnoreCase(command.get(2))
                || !"/s".equalsIgnoreCase(command.get(3))
                || !"/c".equalsIgnoreCase(command.get(4))) {
            return Optional.empty();
        }
        String commandLine = command.get(5);
        if (commandLine.length() < 4 || commandLine.charAt(0) != '"' || commandLine.charAt(1) != '"') {
            return Optional.empty();
        }
        int closingQuote = commandLine.indexOf('"', 2);
        if (closingQuote < 0) {
            return Optional.empty();
        }
        try {
            return Optional.of(Path.of(commandLine.substring(2, closingQuote)));
        } catch (InvalidPathException notAPath) {
            return Optional.empty();
        }
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win");
    }

    private static boolean isLinux() {
        return System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("linux");
    }

    private static String requireCommand(String command) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("command must not be blank");
        }
        if (command.indexOf('/') >= 0 || command.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("command must be a simple executable name");
        }
        return command;
    }

    private static boolean isRootOwnedAndNotGroupWorldWritable(Path path) {
        try {
            Object uid = Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS);
            if (!(uid instanceof Number number) || number.longValue() != 0L) return false;
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
            return !permissions.contains(PosixFilePermission.GROUP_WRITE)
                    && !permissions.contains(PosixFilePermission.OTHERS_WRITE);
        } catch (IOException | SecurityException | UnsupportedOperationException unavailable) {
            return false;
        }
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
                    Path realRoot = root.toRealPath();
                    Optional<Path> systemCmd = realRegularFile(root.resolve("System32").resolve("cmd.exe"));
                    if (systemCmd.isPresent() && systemCmd.orElseThrow().startsWith(realRoot)) {
                        return systemCmd.orElseThrow().toString();
                    }
                }
            } catch (InvalidPathException | IOException | SecurityException invalid) {
                // Fail below instead of falling back to ComSpec, PATH or a bare cmd.exe.
            }
        }
        throw new IllegalStateException("Windows command processor must resolve to an existing absolute cmd.exe");
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
