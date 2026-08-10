package com.minos.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedProcessOutputTest {

    @Test
    void drainsUnlimitedStreamsButRetainsOnlyConfiguredBytes(@TempDir Path temp) throws Exception {
        byte[] stdout = "x".repeat(32_000).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] stderr = "y".repeat(24_000).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path out = temp.resolve("stdout.log");
        Path err = temp.resolve("stderr.log");

        BoundedProcessOutput.Capture capture = BoundedProcessOutput.capture(
                new StubProcess(stdout, stderr), out, err, 1024);
        BoundedProcessOutput.Result result = capture.await();

        assertTrue(result.stdoutTruncated());
        assertTrue(result.stderrTruncated());
        assertTrue(Files.size(out) <= 1024);
        assertTrue(Files.size(err) <= 1024);
        assertTrue(Files.readString(out).contains("MINOS output truncated"));
        assertTrue(Files.readString(err).contains("MINOS output truncated"));
    }

    private static final class StubProcess extends Process {
        private final InputStream stdout;
        private final InputStream stderr;

        private StubProcess(byte[] stdout, byte[] stderr) {
            this.stdout = new ByteArrayInputStream(stdout);
            this.stderr = new ByteArrayInputStream(stderr);
        }

        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        @Override public InputStream getInputStream() { return stdout; }
        @Override public InputStream getErrorStream() { return stderr; }
        @Override public int waitFor() { return 0; }
        @Override public int exitValue() { return 0; }
        @Override public void destroy() { }
    }
}
