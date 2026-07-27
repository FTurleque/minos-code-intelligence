package com.minos.store;

import com.minos.semantic.SemanticDocument;
import com.minos.semantic.SemanticDocumentKind;
import com.minos.semantic.SemanticVector;
import com.minos.semantic.SemanticVectorStore;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Local versioned M20 vector store. Rebuild from active snapshots is always authoritative. */
public final class FileSemanticVectorStore implements SemanticVectorStore {

    private static final int MAGIC = 0x4D53454D; // MSEM
    private static final int FORMAT_VERSION = 1;
    private static final int MAX_DOCUMENTS = 2_000_000;
    private static final int MAX_DIMENSIONS = 16_384;
    private static final int MAX_STRING_BYTES = 16 * 1024 * 1024;

    private final Path root;

    public FileSemanticVectorStore(Path root) throws IOException {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        Files.createDirectories(this.root);
    }

    @Override
    public Optional<IndexSnapshot> load(String projectId) throws IOException {
        requireText(projectId, "projectId");
        Path file = indexFile(projectId);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (input.readInt() != MAGIC) throw new IOException("invalid semantic index magic: " + file);
            int version = input.readInt();
            if (version != FORMAT_VERSION) throw new IOException("unsupported semantic index version: " + version);
            String storedProject = readString(input);
            String snapshotId = readString(input);
            String providerId = readString(input);
            String modelId = readString(input);
            int dimensions = input.readInt();
            long builtAt = input.readLong();
            int count = input.readInt();
            if (!projectId.equals(storedProject)) throw new IOException("semantic index project mismatch");
            if (dimensions < 1 || dimensions > MAX_DIMENSIONS) throw new IOException("invalid semantic dimensions");
            if (count < 0 || count > MAX_DOCUMENTS) throw new IOException("invalid semantic document count");
            List<IndexedDocument> documents = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                SemanticDocument document = new SemanticDocument(
                        readString(input), readString(input), storedProject, snapshotId,
                        SemanticDocumentKind.valueOf(readString(input)),
                        readString(input), nullable(readString(input)), input.readInt(), input.readInt(),
                        readString(input), readString(input));
                int vectorSize = input.readInt();
                if (vectorSize != dimensions) throw new IOException("semantic vector dimensions mismatch");
                List<Double> vector = new ArrayList<>(dimensions);
                for (int d = 0; d < dimensions; d++) vector.add(input.readDouble());
                documents.add(new IndexedDocument(document, new SemanticVector(document.stableKey(), vector)));
            }
            if (input.read() != -1) throw new IOException("unexpected trailing semantic index data");
            return Optional.of(new IndexSnapshot(storedProject, snapshotId, providerId, modelId,
                    dimensions, builtAt, documents));
        } catch (EOFException exception) {
            throw new IOException("truncated semantic index: " + file, exception);
        }
    }

    @Override
    public void replace(IndexSnapshot snapshot) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        if (snapshot.documents().size() > MAX_DOCUMENTS) throw new IOException("semantic index exceeds document limit");
        if (snapshot.dimensions() > MAX_DIMENSIONS) throw new IOException("semantic index exceeds dimension limit");
        Path directory = projectDirectory(snapshot.projectId());
        Files.createDirectories(directory);
        Path target = directory.resolve("index-v1.bin");
        Path temporary = Files.createTempFile(directory, "index-v1-", ".tmp");
        try {
            List<IndexedDocument> ordered = snapshot.documents().stream()
                    .sorted(Comparator.comparing(value -> value.document().stableKey()))
                    .toList();
            try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))) {
                output.writeInt(MAGIC);
                output.writeInt(FORMAT_VERSION);
                writeString(output, snapshot.projectId());
                writeString(output, snapshot.snapshotId());
                writeString(output, snapshot.providerId());
                writeString(output, snapshot.modelId());
                output.writeInt(snapshot.dimensions());
                output.writeLong(snapshot.builtAtEpochMilli());
                output.writeInt(ordered.size());
                for (IndexedDocument indexed : ordered) {
                    SemanticDocument document = indexed.document();
                    writeString(output, document.id());
                    writeString(output, document.stableKey());
                    writeString(output, document.kind().name());
                    writeString(output, document.sourceId());
                    writeString(output, document.fileId() == null ? "" : document.fileId());
                    output.writeInt(document.startLine());
                    output.writeInt(document.endLine());
                    writeString(output, document.content());
                    writeString(output, document.checksum());
                    output.writeInt(indexed.vector().dimensions());
                    for (double value : indexed.vector().values()) output.writeDouble(value);
                }
            }
            moveAtomically(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public void delete(String projectId) throws IOException {
        requireText(projectId, "projectId");
        Files.deleteIfExists(indexFile(projectId));
    }

    public long sizeBytes(String projectId) throws IOException {
        Path file = indexFile(projectId);
        return Files.isRegularFile(file) ? Files.size(file) : 0L;
    }

    public Path root() {
        return root;
    }

    private Path indexFile(String projectId) {
        return projectDirectory(projectId).resolve("index-v1.bin");
    }

    private Path projectDirectory(String projectId) {
        return root.resolve(safeProjectDirectory(projectId));
    }

    private static String safeProjectDirectory(String projectId) {
        requireText(projectId, "projectId");
        if (!projectId.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("projectId contains unsupported path characters");
        }
        return projectId;
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = Objects.requireNonNull(value, "value").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) throw new IOException("semantic index string exceeds safety limit");
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_STRING_BYTES) throw new IOException("invalid semantic index string length");
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new EOFException("truncated semantic index string");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String nullable(String value) {
        return value.isEmpty() ? null : value;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }
}
