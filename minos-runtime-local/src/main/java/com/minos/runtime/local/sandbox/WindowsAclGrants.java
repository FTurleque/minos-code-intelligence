package com.minos.runtime.local.sandbox;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The single place the Windows sandbox backend launches {@code icacls}.
 *
 * <p>A grant is additive ({@code /grant}, never {@code /grant:r}, never a whole-DACL rewrite) and names one
 * identity by SID; a removal ({@code /remove:g}) touches the grants of that one identity only. Both leave every
 * other entry of the DACL as it is, restrictions an administrator put there included. The process runs without a
 * shell, by absolute path under the Windows directory, with a bounded wait; its output is not read. Whatever goes
 * wrong -- no Windows directory, an identity or rights that are not what they claim, a process that cannot start,
 * does not end in time, is interrupted or exits non-zero -- the answer is {@code false}, "not granted", and the
 * caller fails.</p>
 */
final class WindowsAclGrants {

    static final Duration TIMEOUT = Duration.ofSeconds(10);

    private static final Pattern SID = Pattern.compile("S-1-\\d+(?:-\\d+)+");
    private static final Pattern RIGHTS = Pattern.compile("(?:\\((?:OI|CI|IO|NP|I)\\))*(?:\\([A-Z,]+\\)|[A-Z]+)");

    /** Starts a process; a seam so the refusals can be tested on any system. */
    @FunctionalInterface
    interface Launcher {
        Process start(List<String> command) throws IOException;
    }

    private static final WindowsAclGrants SYSTEM = new WindowsAclGrants(
            () -> System.getenv("SystemRoot"),
            command -> new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start(),
            TIMEOUT);

    private final Supplier<String> systemRoot;
    private final Launcher launcher;
    private final Duration timeout;

    WindowsAclGrants(Supplier<String> systemRoot, Launcher launcher, Duration timeout) {
        this.systemRoot = Objects.requireNonNull(systemRoot, "systemRoot");
        this.launcher = Objects.requireNonNull(launcher, "launcher");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    static WindowsAclGrants system() {
        return SYSTEM;
    }

    /** Adds {@code rights} for the identity {@code sid} on {@code target}; {@code false} if not granted. */
    boolean grant(Path target, String sid, String rights) {
        if (!SID.matcher(String.valueOf(sid)).matches() || !RIGHTS.matcher(String.valueOf(rights)).matches()) {
            return false;
        }
        return run(target, "/grant", "*" + sid + ":" + rights);
    }

    /** Removes the grants of the identity {@code sid} on {@code target}; {@code false} if not done. */
    boolean remove(Path target, String sid) {
        if (!SID.matcher(String.valueOf(sid)).matches()) return false;
        return run(target, "/remove:g", "*" + sid);
    }

    private boolean run(Path target, String operation, String subject) {
        String root = systemRoot.get();
        if (root == null || root.isBlank()) return false;
        List<String> command = List.of(
                Path.of(root, "System32", "icacls.exe").toString(),
                Objects.requireNonNull(target, "target").toString(), operation, subject, "/q");
        Process process;
        try {
            process = launcher.start(command);
        } catch (IOException cannotStart) {
            return false;
        }
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return false;
            }
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            return false;
        }
        return process.exitValue() == 0;
    }
}
