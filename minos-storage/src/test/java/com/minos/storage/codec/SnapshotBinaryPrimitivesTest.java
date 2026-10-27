package com.minos.storage.codec;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SnapshotBinaryPrimitivesTest {

    private static final int MAGIC = 0x4D4E4150;

    @Test
    void aStringIsWrittenAsItsLengthThenItsUtf16Chars() throws IOException {
        byte[] bytes = written(output -> SnapshotBinaryPrimitives.writeString(output, "é😀"));

        assertArrayEquals(new byte[] {0, 0, 0, 3, 0, (byte) 0xE9, (byte) 0xD8, 0x3D, (byte) 0xDE, 0x00}, bytes);
        assertEquals("é😀", SnapshotBinaryPrimitives.readRequiredString(input(bytes), "field"));
    }

    @Test
    void nullIsWrittenAsMinusOneAndReadBackAsNull() throws IOException {
        byte[] bytes = written(output -> SnapshotBinaryPrimitives.writeString(output, null));

        assertArrayEquals(new byte[] {-1, -1, -1, -1}, bytes);
        assertNull(SnapshotBinaryPrimitives.readString(input(bytes)));
    }

    @Test
    void aMissingOrBlankRequiredStringIsRefusedByItsFieldName() throws IOException {
        byte[] absent = written(output -> SnapshotBinaryPrimitives.writeString(output, null));
        byte[] blank = written(output -> SnapshotBinaryPrimitives.writeString(output, "  "));

        IOException missing = assertThrows(IOException.class,
                () -> SnapshotBinaryPrimitives.readRequiredString(input(absent), "snapshotId"));
        IOException empty = assertThrows(IOException.class,
                () -> SnapshotBinaryPrimitives.readRequiredString(input(blank), "sha256"));

        assertEquals("snapshotId must not be blank", missing.getMessage());
        assertEquals("sha256 must not be blank", empty.getMessage());
    }

    @Test
    void aStringLongerThanTheLimitIsRefusedOnWriteAndOnRead() throws IOException {
        String tooLong = "x".repeat(SnapshotBinaryPrimitives.MAX_STRING_CHARS + 1);
        IOException write = assertThrows(IOException.class,
                () -> SnapshotBinaryPrimitives.writeString(new DataOutputStream(new ByteArrayOutputStream()), tooLong));
        byte[] declared = written(output -> output.writeInt(SnapshotBinaryPrimitives.MAX_STRING_CHARS + 1));
        byte[] negative = written(output -> output.writeInt(-2));

        IOException read = assertThrows(IOException.class, () -> SnapshotBinaryPrimitives.readString(input(declared)));
        IOException invalid = assertThrows(IOException.class, () -> SnapshotBinaryPrimitives.readString(input(negative)));

        assertEquals("string exceeds snapshot limit", write.getMessage());
        assertEquals("invalid string length in symbol snapshot: " + (SnapshotBinaryPrimitives.MAX_STRING_CHARS + 1),
                read.getMessage());
        assertEquals("invalid string length in symbol snapshot: -2", invalid.getMessage());
    }

    @Test
    void aCountOutsideItsBoundsIsRefused() throws IOException {
        assertEquals(7, SnapshotBinaryPrimitives.readCount(input(written(output -> output.writeInt(7))), 7, "symbol count"));

        IOException above = assertThrows(IOException.class, () -> SnapshotBinaryPrimitives.readCount(
                input(written(output -> output.writeInt(8))), 7, "symbol count"));
        IOException negative = assertThrows(IOException.class, () -> SnapshotBinaryPrimitives.readCount(
                input(written(output -> output.writeInt(-1))), 7, "symbol count"));

        assertEquals("invalid symbol count: 8", above.getMessage());
        assertEquals("invalid symbol count: -1", negative.getMessage());
    }

    @Test
    void aHeaderIsReadOnlyBehindItsMagicNumber() throws IOException {
        byte[] header = written(output -> {
            output.writeInt(MAGIC);
            output.writeInt(2);
        });
        byte[] wrong = written(output -> {
            output.writeInt(MAGIC + 1);
            output.writeInt(2);
        });

        assertEquals(2, SnapshotBinaryPrimitives.readHeaderVersion(input(header), MAGIC, "active snapshot pointer"));
        IOException refusal = assertThrows(IOException.class,
                () -> SnapshotBinaryPrimitives.readHeaderVersion(input(wrong), MAGIC, "active snapshot pointer"));
        assertEquals("invalid active snapshot pointer magic", refusal.getMessage());
    }

    private interface Writer {
        void write(DataOutputStream output) throws IOException;
    }

    private static byte[] written(Writer writer) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            writer.write(output);
        }
        return bytes.toByteArray();
    }

    private static DataInputStream input(byte[] bytes) {
        return new DataInputStream(new ByteArrayInputStream(bytes));
    }
}
