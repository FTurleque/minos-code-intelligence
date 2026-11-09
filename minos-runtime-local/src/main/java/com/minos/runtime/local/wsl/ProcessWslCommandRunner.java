package com.minos.runtime.local.wsl;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs a {@code wsl.exe} command as a real child process.
 *
 * <p>The executable must be an absolute path; the environment is replaced by {@link WslCommandEnvironment};
 * standard input is closed and standard error discarded. Standard output is bounded by
 * {@link #MAX_OUTPUT_BYTES}; the process tree is destroyed when it exceeds that bound or the timeout.
 * Failures carry fixed messages, without paths.</p>
 */
public final class ProcessWslCommandRunner implements WslCommandRunner {

    /** Upper bound of the standard output read from one command. */
    public static final int MAX_OUTPUT_BYTES = 1 << 20;

    @Override
    public WslCommandResult run(List<String> command, Duration timeout) throws IOException {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(timeout, "timeout");
        requireAbsoluteExecutable(command);
        ProcessBuilder builder = new ProcessBuilder(command);
        WslCommandEnvironment.apply(builder);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process = builder.start();
        try (OutputStream stdin = process.getOutputStream()) {
            stdin.flush();
        }
        AtomicReference<byte[]> output = new AtomicReference<>();
        AtomicBoolean overflow = new AtomicBoolean();
        AtomicReference<IOException> readFailure = new AtomicReference<>();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (InputStream stdout = process.getInputStream()) {
                byte[] bytes = stdout.readNBytes(MAX_OUTPUT_BYTES + 1);
                if (bytes.length > MAX_OUTPUT_BYTES) {
                    overflow.set(true);
                    destroyTree(process);
                } else {
                    output.set(bytes);
                }
            } catch (IOException failure) {
                readFailure.set(failure);
            }
        });
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                destroyTree(process);
                throw new WslCommandTimeoutException("wsl.exe command timed out");
            }
            if (!reader.join(timeout.isZero() ? Duration.ofMillis(1) : timeout)) {
                destroyTree(process);
                throw new IOException("wsl.exe output was not closed in time");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            destroyTree(process);
            throw new InterruptedIOException("wsl.exe command interrupted");
        }
        if (overflow.get()) {
            throw new IOException("wsl.exe output exceeds " + MAX_OUTPUT_BYTES + " bytes");
        }
        if (readFailure.get() != null || output.get() == null) {
            throw new IOException("wsl.exe output could not be read");
        }
        return new WslCommandResult(process.exitValue(), output.get());
    }

    private static void requireAbsoluteExecutable(List<String> command) {
        if (command.isEmpty() || command.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("command must name an executable and contain no null argument");
        }
        boolean absolute;
        try {
            absolute = Path.of(command.get(0)).isAbsolute();
        } catch (InvalidPathException invalid) {
            absolute = false;
        }
        if (!absolute) {
            throw new IllegalArgumentException("the wsl.exe executable must be an absolute path");
        }
    }

    private static void destroyTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }
}
