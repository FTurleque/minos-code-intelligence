package com.minos.storage.codec;

import java.io.IOException;

/** Counts the bytes {@link SnapshotEncodingSink.StreamSink} would write, with the same string checks, and writes nothing. */
final class SnapshotEncodingSinkCounter implements SnapshotEncodingSink {
    private final SnapshotStringEncoding encoding;
    private long bytes;

    SnapshotEncodingSinkCounter(SnapshotStringEncoding encoding) { this.encoding = encoding; }

    long bytes() { return bytes; }

    @Override public void writeInt(int value) { bytes += Integer.BYTES; }
    @Override public void writeLong(long value) { bytes += Long.BYTES; }
    @Override public void writeByte(int value) { bytes += Byte.BYTES; }
    @Override public void writeBoolean(boolean value) { bytes += Byte.BYTES; }
    @Override public void writeDouble(double value) { bytes += Double.BYTES; }
    @Override public void writeString(String value) throws IOException {
        bytes += Integer.BYTES;
        if (value == null) return;
        SnapshotBinaryPrimitives.requireWritableString(value);
        bytes += encoding == SnapshotStringEncoding.UTF16_CHARS
                ? (long) Character.BYTES * value.length()
                : SnapshotStringEncoding.utf8Length(value);
    }
}
