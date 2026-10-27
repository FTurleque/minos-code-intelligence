package com.minos.storage.local.store;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Field primitives of the historical binary formats: a versioned header, a UTF-16 string (length then chars, -1 for
 * null) and a bounded count. The snapshot codecs and the active snapshot pointer are made of them, so both keep their
 * exact bytes.
 */
public final class SnapshotBinaryPrimitives {

    /** Longest string, in UTF-16 code units, that a snapshot or a pointer holds. */
    static final int MAX_STRING_CHARS = 8 * 1024 * 1024;

    private SnapshotBinaryPrimitives() {
    }

    /** Reads a magic number, refused unless it is {@code expectedMagic}, then returns the version that follows. */
    public static int readHeaderVersion(DataInputStream input, int expectedMagic, String name) throws IOException {
        if (input.readInt() != expectedMagic) {
            throw new IOException("invalid " + name + " magic");
        }
        return input.readInt();
    }

    /** Writes a string as its UTF-16 length then its chars, or -1 for null. */
    public static void writeString(DataOutputStream output, String value) throws IOException {
        if (value == null) {
            output.writeInt(-1);
            return;
        }
        requireWritableString(value);
        output.writeInt(value.length());
        for (int index = 0; index < value.length(); index++) {
            output.writeChar(value.charAt(index));
        }
    }

    /** Reads a string written by {@link #writeString}, refused when null or blank. */
    public static String readRequiredString(DataInputStream input, String fieldName) throws IOException {
        String value = readString(input);
        if (value == null || value.isBlank()) {
            throw new IOException(fieldName + " must not be blank");
        }
        return value;
    }

    /** Reads a count, refused when negative or above {@code maximum}. */
    public static int readCount(DataInputStream input, int maximum, String name) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > maximum) {
            throw new IOException("invalid " + name + ": " + count);
        }
        return count;
    }

    static void requireWritableString(String value) throws IOException {
        if (value.length() > MAX_STRING_CHARS) {
            throw new IOException("string exceeds snapshot limit");
        }
    }

    static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length == -1) {
            return null;
        }
        if (length < 0 || length > MAX_STRING_CHARS) {
            throw new IOException("invalid string length in symbol snapshot: " + length);
        }
        StringBuilder value = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            value.append(input.readChar());
        }
        return value.toString();
    }
}
