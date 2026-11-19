package com.minos.storage.codec;

import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Destination of one encoding pass. The writers traverse a snapshot once, against either the real stream or a
 * counter: the computed size and the written size cannot drift apart.
 */
sealed interface SnapshotEncodingSink permits SnapshotEncodingSink.StreamSink, SnapshotEncodingSinkCounter {

    void writeInt(int value) throws IOException;

    void writeLong(long value) throws IOException;

    void writeByte(int value) throws IOException;

    void writeBoolean(boolean value) throws IOException;

    void writeDouble(double value) throws IOException;

    void writeString(String value) throws IOException;

    /** Writes to a stream, strings in the format's encoding. */
    record StreamSink(DataOutputStream output, SnapshotStringEncoding encoding) implements SnapshotEncodingSink {
        @Override public void writeInt(int value) throws IOException { output.writeInt(value); }
        @Override public void writeLong(long value) throws IOException { output.writeLong(value); }
        @Override public void writeByte(int value) throws IOException { output.writeByte(value); }
        @Override public void writeBoolean(boolean value) throws IOException { output.writeBoolean(value); }
        @Override public void writeDouble(double value) throws IOException { output.writeDouble(value); }
        @Override public void writeString(String value) throws IOException {
            if (encoding == SnapshotStringEncoding.UTF16_CHARS) {
                SnapshotBinaryPrimitives.writeString(output, value);
                return;
            }
            if (value == null) {
                output.writeInt(-1);
                return;
            }
            SnapshotBinaryPrimitives.requireWritableString(value);
            byte[] bytes = SnapshotStringEncoding.utf8(value);
            output.writeInt(bytes.length);
            output.write(bytes);
        }
    }
}
