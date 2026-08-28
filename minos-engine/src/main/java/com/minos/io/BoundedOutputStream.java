package com.minos.io;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

/**
 * Output stream that refuses the write which would cross a hard byte ceiling.
 *
 * <p>The limit is enforced while bytes are emitted, so callers never create an oversized
 * temporary payload and discover the violation only after publication or a post-write size check.</p>
 */
public final class BoundedOutputStream extends FilterOutputStream {

    private final long maximumBytes;
    private final String boundary;
    private long writtenBytes;

    public BoundedOutputStream(OutputStream output, long maximumBytes, String boundary) {
        super(Objects.requireNonNull(output, "output"));
        if (maximumBytes < 1L) {
            throw new IllegalArgumentException("maximumBytes must be positive");
        }
        this.maximumBytes = maximumBytes;
        this.boundary = boundary == null || boundary.isBlank() ? "output" : boundary;
    }

    @Override
    public void write(int value) throws IOException {
        requireCapacity(1L);
        out.write(value);
        writtenBytes++;
    }

    @Override
    public void write(byte[] buffer, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, buffer.length);
        if (length == 0) return;
        requireCapacity(length);
        out.write(buffer, offset, length);
        writtenBytes += length;
    }

    public long writtenBytes() {
        return writtenBytes;
    }

    private void requireCapacity(long count) throws IOException {
        long next;
        try {
            next = Math.addExact(writtenBytes, count);
        } catch (ArithmeticException exception) {
            throw new IOException(boundary + " byte counter overflow", exception);
        }
        if (next > maximumBytes) {
            throw new IOException(boundary + " exceeds byte limit: " + next + "/" + maximumBytes);
        }
    }
}
