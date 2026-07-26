package com.minos.store;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Owns the historical active.pointer binary format and its atomic promotion. */
public final class ActiveSnapshotRepository {

    public static final int FORMAT_VERSION_V1 = 1;
    public static final int FORMAT_VERSION_V2 = 2;

    private static final int POINTER_MAGIC = 0x4D4E4150;
    private static final int MAX_SYMBOLS = 10_000_000;
    private static final int MAX_OCCURRENCES = 100_000_000;
    private static final int MAX_RELATIONSHIPS = 100_000_000;
    private static final int MAX_STRING_CHARS = 8 * 1024 * 1024;
    private static final String ACTIVE_FILE = "active.pointer";

    private final SnapshotRepository repository;

    public ActiveSnapshotRepository(SnapshotRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public Optional<SnapshotDescriptor> read(UUID projectId) throws IOException {
        Objects.requireNonNull(projectId, "projectId");
        Path pointerFile = repository.projectDirectory(projectId).resolve(ACTIVE_FILE);
        if (!Files.isRegularFile(pointerFile)) {
            return Optional.empty();
        }
        return Optional.of(readPointer(pointerFile));
    }

    public void promote(UUID projectId, SnapshotDescriptor descriptor) throws IOException {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(descriptor, "descriptor");
        Path temporary = repository.createTemporaryPointer(projectId);
        try {
            writePointer(temporary, descriptor);
            repository.replaceActivePointer(projectId, temporary, ACTIVE_FILE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public String activeFileName() {
        return ACTIVE_FILE;
    }

    private static void writePointer(Path file, SnapshotDescriptor pointer) throws IOException {
        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(
                Files.newOutputStream(file)
        ))) {
            output.writeInt(POINTER_MAGIC);
            output.writeInt(pointer.formatVersion());
            writeString(output, pointer.snapshotId());
            writeString(output, pointer.fileName());
            writeString(output, pointer.sha256());
            output.writeInt(pointer.symbolCount());
            if (pointer.formatVersion() >= FORMAT_VERSION_V2) {
                output.writeInt(pointer.occurrenceCount());
                output.writeInt(pointer.relationshipCount());
            }
        }
    }

    private static SnapshotDescriptor readPointer(Path file) throws IOException {
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(
                Files.newInputStream(file)
        ))) {
            int version = readHeaderVersion(input, POINTER_MAGIC, "active snapshot pointer");
            if (version != FORMAT_VERSION_V1 && version != FORMAT_VERSION_V2) {
                throw new IOException("unsupported active snapshot pointer version: " + version);
            }
            SnapshotDescriptor pointer = new SnapshotDescriptor(
                    version,
                    readRequiredString(input, "snapshotId"),
                    readRequiredString(input, "fileName"),
                    readRequiredString(input, "sha256"),
                    readCount(input, MAX_SYMBOLS, "symbol count"),
                    version >= FORMAT_VERSION_V2
                            ? readCount(input, MAX_OCCURRENCES, "occurrence count")
                            : 0,
                    version >= FORMAT_VERSION_V2
                            ? readCount(input, MAX_RELATIONSHIPS, "relationship count")
                            : 0
            );
            if (input.read() != -1) {
                throw new IOException("unexpected trailing data in active snapshot pointer");
            }
            return pointer;
        } catch (EOFException exception) {
            throw new IOException("truncated active snapshot pointer", exception);
        }
    }

    private static int readHeaderVersion(
            DataInputStream input,
            int expectedMagic,
            String name
    ) throws IOException {
        if (input.readInt() != expectedMagic) {
            throw new IOException("invalid " + name + " magic");
        }
        return input.readInt();
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        if (value == null) {
            output.writeInt(-1);
            return;
        }
        if (value.length() > MAX_STRING_CHARS) {
            throw new IOException("string exceeds snapshot limit");
        }
        output.writeInt(value.length());
        for (int index = 0; index < value.length(); index++) {
            output.writeChar(value.charAt(index));
        }
    }

    private static String readString(DataInputStream input) throws IOException {
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

    private static String readRequiredString(DataInputStream input, String fieldName) throws IOException {
        String value = readString(input);
        if (value == null || value.isBlank()) {
            throw new IOException(fieldName + " must not be blank");
        }
        return value;
    }

    private static int readCount(DataInputStream input, int maximum, String name) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > maximum) {
            throw new IOException("invalid " + name + ": " + count);
        }
        return count;
    }
}
