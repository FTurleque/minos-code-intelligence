package com.minos.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Drains child-process output completely while retaining only a bounded diagnostic prefix. */
public final class BoundedProcessOutput {

    public static final long DEFAULT_MAX_BYTES_PER_STREAM = 8L * 1024L * 1024L;
    private static final byte[] TRUNCATION_MARKER =
            "\n[MINOS output truncated at configured byte limit]\n".getBytes(StandardCharsets.UTF_8);

    private BoundedProcessOutput() {
    }

    public static Capture capture(Process process, Path stdout, Path stderr) throws IOException {
        return capture(process, stdout, stderr, DEFAULT_MAX_BYTES_PER_STREAM);
    }

    public static Capture capture(Process process, Path stdout, Path stderr, long maxBytesPerStream) throws IOException {
        Objects.requireNonNull(process, "process");
        if (maxBytesPerStream < TRUNCATION_MARKER.length + 1L) {
            throw new IllegalArgumentException("maxBytesPerStream is too small");
        }
        Drainer out = new Drainer(process.getInputStream(), stdout, maxBytesPerStream, "stdout");
        Drainer err = new Drainer(process.getErrorStream(), stderr, maxBytesPerStream, "stderr");
        out.start();
        err.start();
        return new Capture(out, err);
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
            stdout.join();
            stderr.join();
            stdout.rethrow();
            stderr.rethrow();
            return new Result(stdout.truncated, stderr.truncated);
        }
    }

    private static final class Drainer {
        private final InputStream input;
        private final Path target;
        private final long maxBytes;
        private final AtomicReference<IOException> failure = new AtomicReference<>();
        private final Thread thread;
        private volatile boolean truncated;

        private Drainer(InputStream input, Path target, long maxBytes, String name) throws IOException {
            this.input = Objects.requireNonNull(input, "input");
            this.target = target == null ? null : target.toAbsolutePath().normalize();
            this.maxBytes = maxBytes;
            if (this.target != null) {
                Path parent = this.target.getParent();
                if (parent != null) Files.createDirectories(parent);
            }
            this.thread = new Thread(this::drain, "minos-process-" + name + "-drain");
            this.thread.setDaemon(true);
        }

        private void start() {
            thread.start();
        }

        private void join() throws InterruptedException {
            thread.join();
        }

        private void rethrow() throws IOException {
            IOException exception = failure.get();
            if (exception != null) throw exception;
        }

        private void drain() {
            try (InputStream source = input;
                 OutputStream sink = target == null
                         ? OutputStream.nullOutputStream()
                         : Files.newOutputStream(target,
                                 StandardOpenOption.CREATE,
                                 StandardOpenOption.TRUNCATE_EXISTING,
                                 StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[8192];
                long retained = 0L;
                long payloadLimit = maxBytes - TRUNCATION_MARKER.length;
                int read;
                while ((read = source.read(buffer)) >= 0) {
                    if (read == 0) continue;
                    if (target == null) continue;
                    long remaining = payloadLimit - retained;
                    if (remaining > 0L) {
                        int writable = (int) Math.min((long) read, remaining);
                        sink.write(buffer, 0, writable);
                        retained += writable;
                        if (writable < read) truncated = true;
                    } else {
                        truncated = true;
                    }
                }
                if (target != null && truncated) {
                    sink.write(TRUNCATION_MARKER);
                }
            } catch (IOException exception) {
                failure.compareAndSet(null, exception);
            }
        }
    }
}
