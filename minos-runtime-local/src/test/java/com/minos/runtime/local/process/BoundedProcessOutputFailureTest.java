package com.minos.runtime.local.process;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Refus, troncature partielle et chemins d'erreur de {@link BoundedProcessOutput}, sans processus réel. */
class BoundedProcessOutputFailureTest {

    private static final String MARKER = "\n[MINOS output truncated at configured byte limit]\n";

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    void aLimitThatCannotHoldTheTruncationMarkerIsRefusedAndReleasesBothDiagnosticFiles(@TempDir Path temp)
            throws Exception {
        Path out = temp.resolve("logs").resolve("stdout.log");
        Path err = temp.resolve("logs").resolve("stderr.log");
        StubProcess process = new StubProcess(new ByteArrayInputStream(ascii("o")), new ByteArrayInputStream(ascii("e")));

        long tooSmall = MARKER.length();

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> BoundedProcessOutput.capture(process, out, err, tooSmall));

        assertEquals("maxBytesPerStream is too small", refusal.getMessage());
        assertEquals(0L, Files.size(out), "nothing may be drained under a refused limit");
        assertEquals(0L, Files.size(err), "nothing may be drained under a refused limit");
        assertTrue(Files.deleteIfExists(out), "the stdout file must have been closed by the refusal");
        assertTrue(Files.deleteIfExists(err), "the stderr file must have been closed by the refusal");
    }

    @Test
    void aMissingProcessIsRefusedBeforeAnyDiagnosticFileIsCreated(@TempDir Path temp) {
        Path out = temp.resolve("stdout.log");
        Path err = temp.resolve("stderr.log");

        assertThrows(NullPointerException.class, () -> BoundedProcessOutput.capture(null, out, err));

        assertFalse(Files.exists(out));
        assertFalse(Files.exists(err));
    }

    @Test
    void outputWithinTheDefaultLimitIsKeptWholeAndAnAbsentTargetDiscardsItsStream(@TempDir Path temp)
            throws Exception {
        Path err = temp.resolve("stderr.log");
        StubProcess process = new StubProcess(
                new ByteArrayInputStream(ascii("discarded")), new ByteArrayInputStream(ascii("kept whole")));

        BoundedProcessOutput.Result result = BoundedProcessOutput.capture(process, null, err).await();

        assertFalse(result.stdoutTruncated());
        assertFalse(result.stderrTruncated());
        assertFalse(result.truncated());
        assertEquals("kept whole", Files.readString(err, StandardCharsets.US_ASCII));
    }

    @Test
    void anExistingDiagnosticFileIsReplacedNotAppendedTo(@TempDir Path temp) throws Exception {
        Path target = Files.writeString(temp.resolve("stdout.log"), "stale content of a previous run");

        try (OutputStream sink = BoundedProcessOutput.openTarget(target)) {
            sink.write(ascii("fresh"));
        }

        assertEquals("fresh", Files.readString(target, StandardCharsets.US_ASCII));
    }

    @Test
    void onlyTheOverflowingStreamIsTruncatedAndTheMarkerFitsInsideTheLimit() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        long limit = MARKER.length() + 8L;
        StubProcess process = new StubProcess(
                new ChunkedInput(ascii("12345"), 2), new ChunkedInput(ascii("abcdefghijklmnopqrstuvwxyz"), 3));

        BoundedProcessOutput.Result result = BoundedProcessOutput.capture(process, out, err, limit).await();

        assertFalse(result.stdoutTruncated());
        assertTrue(result.stderrTruncated());
        assertTrue(result.truncated(), "one truncated stream is enough to report truncation");
        assertArrayEquals(ascii("12345"), out.toByteArray(),
                "a read that yields zero bytes is not the end of the stream");
        assertEquals("abcdefgh" + MARKER, err.toString(StandardCharsets.US_ASCII),
                "the retained prefix is exactly the limit minus the marker, across several short reads");
        assertEquals(limit, err.size());
    }

    @Test
    void theDefaultLimitOverloadDrainsToAlreadyOpenedSinks() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        StubProcess process = new StubProcess(
                new ByteArrayInputStream(ascii("stdout")), new ByteArrayInputStream(ascii("stderr")));

        BoundedProcessOutput.Result result = BoundedProcessOutput.capture(process, out, err).await();

        assertFalse(result.truncated());
        assertEquals("stdout", out.toString(StandardCharsets.US_ASCII));
        assertEquals("stderr", err.toString(StandardCharsets.US_ASCII));
    }

    @Test
    void aNonPositiveDrainTimeoutIsRefused() {
        StubProcess process = new StubProcess(InputStream.nullInputStream(), InputStream.nullInputStream());
        BoundedProcessOutput.Capture capture = BoundedProcessOutput.capture(
                process, OutputStream.nullOutputStream(), OutputStream.nullOutputStream());
        Duration negative = Duration.ofMillis(-1);

        IllegalArgumentException zero = assertThrows(IllegalArgumentException.class,
                () -> capture.await(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> capture.await(negative));
        assertThrows(NullPointerException.class, () -> capture.await(null));

        assertEquals("timeout must be positive", zero.getMessage());
    }

    @Test
    void aSinkThatFailsIsReportedByAwaitInsteadOfBeingSwallowed() {
        StubProcess process = new StubProcess(
                new ByteArrayInputStream(ascii("payload")), InputStream.nullInputStream());
        OutputStream failing = new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                throw new IOException("diagnostic sink is full");
            }
        };
        BoundedProcessOutput.Capture capture = BoundedProcessOutput.capture(
                process, failing, OutputStream.nullOutputStream(), 1024);

        IOException failure = assertThrows(IOException.class, capture::await);

        assertEquals("diagnostic sink is full", failure.getMessage());
    }

    @Test
    void aStderrSinkFailureIsReportedEvenWhenStdoutDrainedCleanly() {
        StubProcess process = new StubProcess(
                InputStream.nullInputStream(), new ByteArrayInputStream(ascii("payload")));
        OutputStream failing = new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                throw new IOException("stderr sink is full");
            }
        };
        BoundedProcessOutput.Capture capture = BoundedProcessOutput.capture(
                process, OutputStream.nullOutputStream(), failing, 1024);

        IOException failure = assertThrows(IOException.class, capture::await);

        assertEquals("stderr sink is full", failure.getMessage());
    }

    /** Rejoué 50 fois : l'issue ne doit dépendre d'aucun ordonnancement des deux fils de drainage. */
    @Test
    void streamsThatNeverQuiesceAreClosedAndReportedWhenTheDrainTimeoutElapses() {
        Duration timeout = Duration.ofMillis(20);
        for (int attempt = 0; attempt < 50; attempt++) {
            BlockingInput stdout = new BlockingInput(false);
            BlockingInput stderr = new BlockingInput(true);
            BoundedProcessOutput.Capture capture = BoundedProcessOutput.capture(
                    new StubProcess(stdout, stderr), OutputStream.nullOutputStream(), OutputStream.nullOutputStream());

            IOException failure = assertThrows(IOException.class, () -> capture.await(timeout));

            assertTrue(failure.getMessage().startsWith("process output streams did not quiesce within "),
                    failure.getMessage());
            assertTrue(stdout.closed.get(), "the stuck stdout stream must be closed to release its drainer");
            assertTrue(stderr.closed.get(), "a stream whose close fails must still have been asked to close");
        }
    }

    /** Délivre au plus {@code chunk} octets par lecture et une lecture vide avant le premier octet. */
    private static final class ChunkedInput extends InputStream {
        private final byte[] content;
        private final int chunk;
        private int position;
        private boolean emptyReadDelivered;

        private ChunkedInput(byte[] content, int chunk) {
            this.content = content;
            this.chunk = chunk;
        }

        @Override
        public int read() {
            return position < content.length ? content[position++] & 0xFF : -1;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            if (!emptyReadDelivered) {
                emptyReadDelivered = true;
                return 0;
            }
            if (position >= content.length) return -1;
            int count = Math.min(Math.min(chunk, length), content.length - position);
            System.arraycopy(content, position, buffer, offset, count);
            position += count;
            return count;
        }
    }

    /** Bloque toute lecture jusqu'à sa fermeture, comme le tube d'un descendant qui ne se termine pas. */
    private static final class BlockingInput extends InputStream {
        private final CountDownLatch released = new CountDownLatch(1);
        private final AtomicBoolean closed = new AtomicBoolean();
        private final boolean closeFails;

        private BlockingInput(boolean closeFails) {
            this.closeFails = closeFails;
        }

        @Override
        public int read() throws IOException {
            try {
                released.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while blocked", interrupted);
            }
            return -1;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            return read();
        }

        @Override
        public void close() throws IOException {
            closed.set(true);
            released.countDown();
            if (closeFails) throw new IOException("close failed");
        }
    }

    private static final class StubProcess extends Process {
        private final InputStream stdout;
        private final InputStream stderr;

        private StubProcess(InputStream stdout, InputStream stderr) {
            this.stdout = stdout;
            this.stderr = stderr;
        }

        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        @Override public InputStream getInputStream() { return stdout; }
        @Override public InputStream getErrorStream() { return stderr; }
        @Override public int waitFor() { return 0; }
        @Override public int exitValue() { return 0; }
        @Override public void destroy() { }
    }
}
