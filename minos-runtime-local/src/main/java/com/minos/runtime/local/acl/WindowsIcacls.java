package com.minos.runtime.local.acl;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The single place MINOS launches {@code icacls}: the platform access control of private storage and the grants
 * of the Windows sandbox both go through it.
 *
 * <p>The process runs without a shell and without the environment of MINOS, by absolute path under the Windows
 * directory, its output discarded, with a bounded wait after which it is killed. The target must be an absolute
 * path without a control character or any of {@code *?"<>|}, and without a name that ends with a dot or a space
 * or is a device name; it is passed in its extended-length form ({@code \\?\C:\...}, {@code \\?\UNC\server\...}),
 * because {@code icacls} fails (exit 3) on a path longer than 260 characters otherwise. Whatever goes wrong --
 * a path refused, no Windows directory, a process that cannot start, does not end in time, is interrupted or exits
 * non-zero -- the call fails with the caller's refusal text, never with a path or the output of the process.</p>
 */
public final class WindowsIcacls {

    /** How long an {@code icacls} child may run. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    private static final String EXTENDED_PREFIX = "\\\\?\\";
    private static final String WINDOWS_DIRECTORY_VARIABLE = "SystemRoot";

    /** Starts a process; a seam so that every refusal can be exercised on any system. */
    @FunctionalInterface
    public interface Launcher {
        Process start(List<String> command) throws IOException;
    }

    private static final WindowsIcacls SYSTEM = new WindowsIcacls(
            () -> System.getenv(WINDOWS_DIRECTORY_VARIABLE), WindowsIcacls::startWithoutInheritedEnvironment,
            DEFAULT_TIMEOUT);

    private final Supplier<String> systemRoot;
    private final Launcher launcher;
    private final Duration timeout;

    public WindowsIcacls(Supplier<String> systemRoot, Launcher launcher, Duration timeout) {
        this.systemRoot = Objects.requireNonNull(systemRoot, "systemRoot");
        this.launcher = Objects.requireNonNull(launcher, "launcher");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    /** The launcher of this process: {@code SystemRoot} from the environment, a real child process. */
    public static WindowsIcacls system() {
        return SYSTEM;
    }

    /**
     * Fails, before anything is started, if {@code target} is not a path {@code icacls} may be given or if the
     * Windows directory is unknown.
     */
    public void requireUsable(Path target, String refusal) throws IOException {
        checkedPath(target, refusal);
        windowsDirectory(refusal);
    }

    /** Runs {@code icacls <target> <arguments...>} and fails unless it exits with 0 in time. */
    public void run(Path target, List<String> arguments, String refusal) throws IOException {
        String path = checkedPath(target, refusal);
        String windows = windowsDirectory(refusal);
        List<String> command = new ArrayList<>();
        command.add(Path.of(windows, "System32", "icacls.exe").toString());
        command.add(extendedLength(path));
        command.addAll(arguments);
        Process process;
        try {
            process = launcher.start(command);
        } catch (IOException _) {
            throw new IOException(refusal + ": icacls cannot be started");
        }
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IOException(refusal + ": timed out");
            }
        } catch (InterruptedException _) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException(refusal + ": interrupted");
        }
        if (process.exitValue() != 0) {
            throw new IOException(refusal + " (exit " + process.exitValue() + ")");
        }
    }

    /**
     * The extended-length form of an absolute Windows path: {@code C:\x} becomes {@code \\?\C:\x} and
     * {@code \\server\share\x} becomes {@code \\?\UNC\server\share\x}. A path already in that form, or not in a
     * Windows form at all (a test on another system), is returned as it is.
     */
    public static String extendedLength(String path) {
        if (path.startsWith(EXTENDED_PREFIX)) return path;
        if (path.length() >= 3 && Character.isLetter(path.charAt(0)) && path.charAt(1) == ':'
                && path.charAt(2) == '\\') {
            return EXTENDED_PREFIX + path;
        }
        if (path.startsWith("\\\\")) return EXTENDED_PREFIX + "UNC\\" + path.substring(2);
        return path;
    }

    /**
     * The target as {@code icacls} receives it, after refusing what the extended-length form would read differently
     * from Java: the prefix turns off the Win32 normalization, so a name ending with a dot or a space, or a reserved
     * device name, would designate another object than the one Java just rewrote.
     */
    private static String checkedPath(Path target, String refusal) throws IOException {
        Objects.requireNonNull(target, "target");
        String path = target.toString();
        if (!target.isAbsolute() || path.chars().anyMatch(c -> c < 0x20 || "*?\"<>|".indexOf(c) >= 0)) {
            throw new IOException(refusal + ": unsupported path");
        }
        for (Path element : target) {
            String name = element.toString();
            if (name.endsWith(".") || name.endsWith(" ") || RESERVED_NAME.matcher(name).matches()) {
                throw new IOException(refusal + ": unsupported path");
            }
        }
        return path;
    }

    /** A Windows device name, alone or followed by an extension, in any case. */
    private static final Pattern RESERVED_NAME =
            Pattern.compile("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?");

    /**
     * Starts {@code icacls} with no environment but the Windows directory: a system tool needs nothing else, and the
     * secrets a MINOS process may hold in its environment are not handed to any child.
     */
    static Process startWithoutInheritedEnvironment(List<String> command) throws IOException {
        return builderWithoutInheritedEnvironment(command).start();
    }

    static ProcessBuilder builderWithoutInheritedEnvironment(List<String> command) {
        ProcessBuilder builder = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD);
        Map<String, String> environment = builder.environment();
        // System.getenv ignores the case of the name on Windows; the builder's map does not find "SystemRoot" there.
        String windows = System.getenv(WINDOWS_DIRECTORY_VARIABLE);
        environment.clear();
        if (windows != null) environment.put(WINDOWS_DIRECTORY_VARIABLE, windows);
        return builder;
    }

    private String windowsDirectory(String refusal) throws IOException {
        String windows = systemRoot.get();
        if (windows == null || windows.isBlank()) {
            throw new IOException(refusal + ": Windows directory unknown");
        }
        return windows;
    }
}
