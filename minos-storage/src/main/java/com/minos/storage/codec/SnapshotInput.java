package com.minos.storage.codec;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Source of one decoding pass: primitive fields straight from the stream, strings decoded in the format's encoding.
 * UTF-8 is decoded strictly (malformed input is reported, never replaced) and each string is bounded in bytes before
 * being read. Counts and required strings are refused with the same messages as the shared framing primitives.
 */
final class SnapshotInput {

    private static final int MAX_STRING_CHARS = SnapshotBinaryPrimitives.MAX_STRING_CHARS;
    /** A string of at most {@link #MAX_STRING_CHARS} UTF-16 code units encodes to at most three bytes per unit. */
    private static final int MAX_STRING_UTF8_BYTES = 3 * MAX_STRING_CHARS;

    private final DataInputStream stream;
    private final SnapshotStringEncoding encoding;
    private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    private final Map<String, String> distinctStrings = new HashMap<>();

    SnapshotInput(DataInputStream stream, SnapshotStringEncoding encoding) {
        this.stream = stream;
        this.encoding = encoding;
    }

    DataInputStream stream() { return stream; }
    int readInt() throws IOException { return stream.readInt(); }
    long readLong() throws IOException { return stream.readLong(); }
    boolean readBoolean() throws IOException { return stream.readBoolean(); }
    int readUnsignedByte() throws IOException { return stream.readUnsignedByte(); }
    double readDouble() throws IOException { return stream.readDouble(); }
    int read() throws IOException { return stream.read(); }

    String readString() throws IOException {
        return shared(readDecodedString());
    }

    /** A string that is neither absent nor blank, refused with a message naming the field. */
    String readRequiredString(String fieldName) throws IOException {
        String value = readString();
        if (value == null || value.isBlank()) {
            throw new IOException(fieldName + " must not be blank");
        }
        return value;
    }

    /** A declared count, bounded by {@code maximum} before anything is allocated for it. */
    int readCount(int maximum, String name) throws IOException {
        return SnapshotBinaryPrimitives.readCount(stream, maximum, name);
    }

    /** An enum constant written by its name; an unknown name is refused with a message naming the field. */
    <E extends Enum<E>> E readEnum(Class<E> enumType, String fieldName) throws IOException {
        String value = readRequiredString(fieldName);
        try {
            return Enum.valueOf(enumType, value);
        } catch (IllegalArgumentException exception) {
            throw new IOException("unsupported " + fieldName + ": " + value, exception);
        }
    }

    /**
     * One instance per distinct string of this decoding pass (ADR 0047, option A). The project id, the origin strings
     * and the file ids are repeated on every entity: decoded as separate instances, they made a reread snapshot retain
     * about 2.5 times the heap of the ingested one. The table lives and dies with this pass; the decoded model is
     * equal, only instance identity changes.
     */
    private String shared(String value) {
        if (value == null) return null;
        String known = distinctStrings.putIfAbsent(value, value);
        return known == null ? value : known;
    }

    private String readDecodedString() throws IOException {
        if (encoding == SnapshotStringEncoding.UTF16_CHARS) return SnapshotBinaryPrimitives.readString(stream);
        int length = stream.readInt();
        if (length == -1) return null;
        if (length < 0 || length > MAX_STRING_UTF8_BYTES) {
            throw new IOException("invalid string length in knowledge snapshot: " + length);
        }
        byte[] bytes = stream.readNBytes(length);
        if (bytes.length != length) throw new EOFException("truncated string in knowledge snapshot");
        String value = decode(bytes);
        if (value.length() > MAX_STRING_CHARS) throw new IOException("string exceeds snapshot limit");
        return value;
    }

    private String decode(byte[] bytes) throws IOException {
        boolean ascii = true;
        for (byte current : bytes) {
            if (current < 0) {
                ascii = false;
                break;
            }
        }
        if (ascii) return new String(bytes, StandardCharsets.US_ASCII);
        try {
            return decoder.reset().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException malformed) {
            throw new IOException("invalid UTF-8 string in knowledge snapshot", malformed);
        }
    }
}
