package com.minos.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/** Drains child-process output completely while retaining only a bounded diagnostic prefix. */
public final class BoundedProcessOutput {

    public static final long DEFAULT_MAX_BYTES_PER_STREAM = 8L * 1024L * 1024L;
    public static final Duration DEFAULT_DRAIN_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration FORCED_CLOSE_GRACE = Duration.ofSeconds(1);
    private static final byte[] TRUNCATION_MARKER =
            "\n[MINOS output truncated at configured byte limit]\n".getBytes(StandardCharsets.UTF_8);

    private BoundedProcessOutput() {
    }

    public static Capture capture(Process process, Path stdout, Path stderr) throws IOException {
        return capture(process, stdout, stderr, DEFAULT_MAX_BYTES_PER_STREAM);
    }

    public static Capture capture(Process process, Path stdout, Path stderr, long maxBytesPerStream) throws IOException {
        Objects.requireNonNull(process, "process");
        OutputStream out = openTarget(stdout);
        boolean success = false;
        try {
            OutputStream err = openTarget(stderr);
            try {
                Capture capture = capture(process, out, err, maxBytesPerStream);
                success = true;
                return capture;
            } catch (RuntimeException | Error failure) {
                err.close();
                throw failure;
            }
        } finally {
            if (!success) out.close();
        }
    }

    /**
     * Starts draining to sinks that were already opened by the caller.
     *
     * <p>Sandboxed callers use this overload so MINOS can open its diagnostic files before any
     * untrusted provider code executes. The provider may later unlink or replace a pathname in a
     * shared writable directory, but it cannot retarget an already-open host file descriptor.</p>
     */
    public static Capture capture(
            Process process,
            OutputStream stdout,
            OutputStream stderr,
            long maxBytesPerStream
    ) {
        Objects.requireNonNull(process, "process");
        requireLimit(maxBytesPerStream);
        Drainer out = new Drainer(process.getInputStream(), stdout, maxBytesPerStream, "stdout");
        Drainer err = new Drainer(process.getErrorStream(), stderr, maxBytesPerStream, "stderr");
        out.start();
        err.start();
        return new Capture(out, err);
    }

    public static Capture capture(Process process, OutputStream stdout, OutputStream stderr) {
        return capture(process, stdout, stderr, DEFAULT_MAX_BYTES_PER_STREAM);
    }

    /** Opens one diagnostic file without ever following an existing symbolic link. */
    static OutputStream openTarget(Path target) throws IOException {
        if (target == null) return OutputStream.nullOutputStream();
        Path normalized = target.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (parent != null) Files.createDirectories(parent);
        // deleteIfExists removes a symlink entry itself rather than its target. CREATE_NEW then
        // closes the replacement race before an untrusted process is started by ProcessIndexerExecutor.
        Files.deleteIfExists(normalized);
        return Channels.newOutputStream(Files.newByteChannel(
                normalized,
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)));
    }

    private static void requireLimit(long maxBytesPerStream) {
        if (maxBytesPerStream < TRUNCATION_MARKER.length + 1L) {
            throw new IllegalArgumentException("maxBytesPerStream is too small");
        }
    }

    public record Result(boolean stdoutTruncated, boolean stderrTruncated) {
        public boolean truncated() {
            return stdoutTruncated || stderrTruncated;
        }
    }

    public static final class Capture {
        private final Drainer stdout;
        private final Drainer stderr;

        private Capture(Drainer stdout, Drainer stderr) {
            this.stdout = stdout;
            this.stderr = stderr;
        }

        public Result await() throws IOException, InterruptedException {
            return await(DEFAULT_DRAIN_TIMEOUT);
        }

        public Result await(Duration timeout) throws IOException, InterruptedException {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            long deadline = System.nanoTime() + timeout.toNanos();
            boolean stdoutDone = stdout.joinUntil(deadline);
            boolean stderrDone = stderr.joinUntil(deadline);
            if (!stdoutDone || !stderrDone) {
                stdout.closeInput();
                stderr.closeInput();
                long forcedDeadline = System.nanoTime() + FORCED_CLOSE_GRACE.toNanos();
                stdout.joinUntil(forcedDeadline);
                stderr.joinUntil(forcedDeadline);
                throw new IOException("process output streams did not quiesce within " + timeout);
            }
            stdout.rethrow();
            stderr.rethrow();
            return new Result(stdout.truncated, stderr.truncated);
        }
    }

    private static final class Drainer {
        private final InputStream input;
        private final OutputStream sink;
        private final long maxBytes;
        private final AtomicReference<IOException> failure = new AtomicReference<>();
        private final Thread thread;
        private volatile boolean truncated;

        private Drainer(InputStream input, OutputStream sink, long maxBytes, String name) {
            this.input = Objects.requireNonNull(input, "input");
            this.sink = Objects.requireNonNull(sink, "sink");
            this.maxBytes = maxBytes;
            this.thread = new Thread(this::drain, "minos-process-" + name + "-drain");
            this.thread.setDaemon(true);
        }

        private void start() {
            thread.start();
        }

        private boolean joinUntil(long deadlineNanos) throws InterruptedException {
            while (thread.isAlive()) {
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0L) return false;
                long millis = Math.max(1L, Math.min(Duration.ofNanos(remaining).toMillis(), 100L));
                thread.join(millis);
            }
            return true;
        }

        private void closeInput() {
            try {
                input.close();
            } catch (IOException exception) {
                failure.compareAndSet(null, exception);
            }
        }

        private void rethrow() throws IOException {
            IOException exception = failure.get();
            if (exception != null) throw exception;
        }

        private void drain() {
            try (InputStream source = input; OutputStream target = sink) {
                byte[] buffer = new byte[8192];
                long retained = 0L;
                long payloadLimit = maxBytes - TRUNCATION_MARKER.length;
                int read;
                while ((read = source.read(buffer)) >= 0) {
                    if (read == 0) continue;
                    long remaining = payloadLimit - retained;
                    if (remaining > 0L) {
                        int writable = (int) Math.min((long) read, remaining);
                        target.write(buffer, 0, writable);
                        retained += writable;
                        if (writable < read) truncated = true;
                    } else {
                        truncated = true;
                    }
                }
                if (truncated) target.write(TRUNCATION_MARKER);
                target.flush();
            } catch (IOException exception) {
                failure.compareAndSet(null, exception);
            }
        }
    }
}
