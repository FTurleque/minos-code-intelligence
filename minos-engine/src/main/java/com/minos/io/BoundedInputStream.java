package com.minos.io;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * Input stream that enforces a hard byte ceiling while the bytes are actually consumed.
 *
 * <p>Unlike a pre-read {@code Files.size()} check, this guard remains effective when a file
 * grows or is replaced after it was opened. One look-ahead byte may be consumed when the
 * payload is exactly at the configured ceiling so EOF can be distinguished from overflow.</p>
 */
public final class BoundedInputStream extends FilterInputStream {

    private final long maximumBytes;
    private final String boundary;
    private long consumedBytes;

    public BoundedInputStream(InputStream input, long maximumBytes, String boundary) {
        super(Objects.requireNonNull(input, "input"));
        if (maximumBytes < 1L) {
            throw new IllegalArgumentException("maximumBytes must be positive");
        }
        this.maximumBytes = maximumBytes;
        this.boundary = boundary == null || boundary.isBlank() ? "input" : boundary;
    }

    @Override
    public int read() throws IOException {
        int value = super.read();
        if (value >= 0) {
            account(1L);
        }
        return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, buffer.length);
        if (length == 0) {
            return 0;
        }
        long remaining = maximumBytes - consumedBytes;
        int boundedLength = (int) Math.min((long) length, Math.min(Integer.MAX_VALUE, remaining + 1L));
        int read = super.read(buffer, offset, Math.max(1, boundedLength));
        if (read > 0) {
            account(read);
        }
        return read;
    }

    @Override
    public long skip(long requested) throws IOException {
        if (requested <= 0L) {
            return 0L;
        }
        long remaining = maximumBytes - consumedBytes;
        long skipped = super.skip(Math.min(requested, remaining + 1L));
        if (skipped > 0L) {
            account(skipped);
        }
        return skipped;
    }

    public long consumedBytes() {
        return consumedBytes;
    }

    private void account(long count) throws IOException {
        try {
            consumedBytes = Math.addExact(consumedBytes, count);
        } catch (ArithmeticException exception) {
            throw new IOException(boundary + " byte counter overflow", exception);
        }
        if (consumedBytes > maximumBytes) {
            throw new IOException(boundary + " exceeds byte limit: " + consumedBytes + "/" + maximumBytes);
        }
    }
}
