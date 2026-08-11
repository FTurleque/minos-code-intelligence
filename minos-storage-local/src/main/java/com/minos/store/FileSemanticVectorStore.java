package com.minos.store;

import com.minos.semantic.SemanticDocument;
import com.minos.semantic.SemanticDocumentKind;
import com.minos.semantic.SemanticVector;
import com.minos.semantic.SemanticVectorStore;

import com.minos.semantic.StaleSemanticSyncException;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** Local versioned vector store. Rebuild from active snapshots is always authoritative. */
public final class FileSemanticVectorStore implements SemanticVectorStore {

    private static final ConcurrentHashMap<Path, ReentrantLock> JVM_SYNC_LOCKS = new ConcurrentHashMap<>();

    private static final int MAGIC = 0x4D53454D; // MSEM
    private static final int LEGACY_FORMAT_VERSION = 1;
    private static final int FORMAT_VERSION = 2;
    private static final String LEGACY_FILE = "index-v1.bin";
    private static final String CURRENT_FILE = "index-v2.bin";
    private static final int MAX_DOCUMENTS = 2_000_000;
    private static final int MAX_DIMENSIONS = 16_384;
    private static final int MAX_STRING_BYTES = 16 * 1024 * 1024;
    private static final long MAX_INDEX_FILE_BYTES = 384L * 1024L * 1024L;
    private static final long MAX_DECODED_STRING_BYTES = 192L * 1024L * 1024L;
    private static final long MAX_DECODED_VECTOR_BYTES = 192L * 1024L * 1024L;
    public static final long DEFAULT_MAX_CACHE_WEIGHT_BYTES = 256L * 1024L * 1024L;

    private final Path root;
    private final long maxCacheWeightBytes;
    private final Object cacheLock = new Object();
    private final LinkedHashMap<String, CacheEntry> cache = new LinkedHashMap<>(16, 0.75f, true);
    private long cacheWeightBytes;
    private long cacheHits;
    private long cacheMisses;
    private long cacheEvictions;

    public FileSemanticVectorStore(Path root) throws IOException {
        this(root, DEFAULT_MAX_CACHE_WEIGHT_BYTES);
    }

    FileSemanticVectorStore(Path root, long maxCacheWeightBytes) throws IOException {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        if (maxCacheWeightBytes < 1L) throw new IllegalArgumentException("maxCacheWeightBytes must be positive");
        this.maxCacheWeightBytes = maxCacheWeightBytes;
        Files.createDirectories(this.root);
    }

    @Override
    public Optional<IndexSnapshot> load(String projectId) throws IOException {
        requireText(projectId, "projectId");
        Path file = readableIndexFile(projectId);
        if (file == null) {
            removeCached(projectId, false);
            return Optional.empty();
        }
        requireIndexFileSize(file);
        CacheEntry cached;
        synchronized (cacheLock) { cached = cache.get(projectId); }
        if (cached != null && cached.matches(file)) {
            synchronized (cacheLock) {
                CacheEntry current = cache.get(projectId);
                if (current == cached) {
                    cacheHits++;
                    return Optional.of(cached.snapshot());
                }
            }
        } else if (cached != null) {
            removeCached(projectId, false);
        }

        synchronized (cacheLock) { cacheMisses++; }
        DecodeBudget budget = new DecodeBudget();
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (input.readInt() != MAGIC) throw new IOException("invalid semantic index magic: " + file);
            int version = input.readInt();
            if (version != LEGACY_FORMAT_VERSION && version != FORMAT_VERSION) {
                throw new IOException("unsupported semantic index version: " + version);
            }
            String storedProject = readString(input, budget);
            String snapshotId = readString(input, budget);
            String providerId = readString(input, budget);
            String modelId = readString(input, budget);
            int dimensions = input.readInt();
            long builtAt = input.readLong();
            int count = input.readInt();
            if (!projectId.equals(storedProject)) throw new IOException("semantic index project mismatch");
            if (dimensions < 1 || dimensions > MAX_DIMENSIONS) throw new IOException("invalid semantic dimensions");
            if (count < 0 || count > MAX_DOCUMENTS) throw new IOException("invalid semantic document count");
            List<IndexedDocument> documents = new ArrayList<>(Math.min(count, 16_384));
            for (int i = 0; i < count; i++) {
                SemanticDocument document = new SemanticDocument(
                        readString(input, budget), readString(input, budget), storedProject, snapshotId,
                        SemanticDocumentKind.valueOf(readString(input, budget)),
                        readString(input, budget), nullable(readString(input, budget)), input.readInt(), input.readInt(),
                        readString(input, budget), readString(input, budget));
                int vectorSize = input.readInt();
                if (vectorSize != dimensions) throw new IOException("semantic vector dimensions mismatch");
                budget.accountVector(vectorSize);
                double[] vector = new double[dimensions];
                for (int d = 0; d < dimensions; d++) {
                    vector[d] = version == LEGACY_FORMAT_VERSION ? input.readDouble() : input.readFloat();
                }
                documents.add(new IndexedDocument(document, SemanticVector.fromArray(document.stableKey(), vector)));
            }
            if (input.read() != -1) throw new IOException("unexpected trailing semantic index data");
            IndexSnapshot snapshot = new IndexSnapshot(storedProject, snapshotId, providerId, modelId,
                    dimensions, builtAt, documents);
            putCached(projectId, CacheEntry.capture(file, snapshot));
            return Optional.of(snapshot);
        } catch (EOFException exception) {
            throw new IOException("truncated semantic index: " + file, exception);
        }
    }

    @Override
    public Optional<IndexMetadata> metadata(String projectId) throws IOException {
        requireText(projectId, "projectId");
        Path file = readableIndexFile(projectId);
        if (file == null) return Optional.empty();
        requireIndexFileSize(file);
        DecodeBudget budget = new DecodeBudget();
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (input.readInt() != MAGIC) throw new IOException("invalid semantic index magic: " + file);
            int version = input.readInt();
            if (version != LEGACY_FORMAT_VERSION && version != FORMAT_VERSION) {
                throw new IOException("unsupported semantic index version: " + version);
            }
            String storedProject = readString(input, budget);
            String snapshotId = readString(input, budget);
            String providerId = readString(input, budget);
            String modelId = readString(input, budget);
            int dimensions = input.readInt();
            long builtAt = input.readLong();
            int count = input.readInt();
            if (!projectId.equals(storedProject)) throw new IOException("semantic index project mismatch");
            if (dimensions < 1 || dimensions > MAX_DIMENSIONS || count < 0 || count > MAX_DOCUMENTS) {
                throw new IOException("invalid semantic index metadata");
            }
            return Optional.of(new IndexMetadata(storedProject, snapshotId, providerId, modelId, dimensions, builtAt, count));
        } catch (EOFException exception) {
            throw new IOException("truncated semantic index metadata: " + file, exception);
        }
    }

    @Override
    public void replace(IndexSnapshot snapshot) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        if (snapshot.documents().size() > MAX_DOCUMENTS) throw new IOException("semantic index exceeds document limit");
        if (snapshot.dimensions() > MAX_DIMENSIONS) throw new IOException("semantic index exceeds dimension limit");
        Path directory = projectDirectory(snapshot.projectId());
        Files.createDirectories(directory);
        Path target = directory.resolve(CURRENT_FILE);
        Path temporary = Files.createTempFile(directory, "index-v2-", ".tmp");
        List<IndexedDocument> ordered = snapshot.documents().stream()
                .sorted(Comparator.comparing(value -> value.document().stableKey()))
                .toList();
        try {
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
                    for (int d = 0; d < indexed.vector().dimensions(); d++) {
                        float compact = (float) indexed.vector().valueAt(d);
                        if (!Float.isFinite(compact)) throw new IOException("semantic vector cannot be represented as float32");
                        output.writeFloat(compact);
                    }
                }
            }
            requireIndexFileSize(temporary);
            moveAtomically(temporary, target);
            Files.deleteIfExists(directory.resolve(LEGACY_FILE));
            // Do not construct a second normalized vector graph while the caller still owns `snapshot`.
            // The next load reconstructs the canonical float32 representation from disk under decode budgets.
            removeCached(snapshot.projectId(), false);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public void replaceConditionally(IndexSnapshot next,
                                      String expectedActiveSnapshotId,
                                      ActiveSnapshotIdReader activeSnapshotReader) throws IOException {
        Objects.requireNonNull(next, "next");
        requireText(expectedActiveSnapshotId, "expectedActiveSnapshotId");
        Objects.requireNonNull(activeSnapshotReader, "activeSnapshotReader");
        Path lockDirectory = root.resolve(".sync-locks").toAbsolutePath().normalize();
        Files.createDirectories(lockDirectory);
        Path lockFile = lockDirectory.resolve(safeProjectDirectory(next.projectId()) + ".lock")
                .toAbsolutePath().normalize();
        if (!lockFile.startsWith(lockDirectory)) {
            throw new IOException("semantic sync lock file escapes store directory");
        }
        ReentrantLock jvmLock = JVM_SYNC_LOCKS.computeIfAbsent(lockFile, ignored -> new ReentrantLock());
        jvmLock.lock();
        try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock fileLock = channel.lock()) {
            Optional<String> currentActive = activeSnapshotReader.read();
            String currentId = currentActive.orElse(null);
            if (!expectedActiveSnapshotId.equals(currentId)) {
                throw new StaleSemanticSyncException(
                        next.projectId(), expectedActiveSnapshotId,
                        currentId != null ? currentId : "absent");
            }
            replace(next);
        } finally {
            jvmLock.unlock();
        }
    }

    @Override
    public void delete(String projectId) throws IOException {
        requireText(projectId, "projectId");
        removeCached(projectId, false);
        Path directory = projectDirectory(projectId);
        Files.deleteIfExists(directory.resolve(CURRENT_FILE));
        Files.deleteIfExists(directory.resolve(LEGACY_FILE));
    }

    public long sizeBytes(String projectId) throws IOException {
        Path file = readableIndexFile(projectId);
        return file == null ? 0L : Files.size(file);
    }

    /** Returns 0 when absent, otherwise the on-disk semantic index format version. */
    public int formatVersion(String projectId) throws IOException {
        Path file = readableIndexFile(projectId);
        if (file == null) return 0;
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (input.readInt() != MAGIC) throw new IOException("invalid semantic index magic: " + file);
            return input.readInt();
        }
    }

    public CacheStats cacheStats() {
        synchronized (cacheLock) {
            return new CacheStats(cacheHits, cacheMisses, cacheEvictions, cache.size(), cacheWeightBytes, maxCacheWeightBytes);
        }
    }

    public Path root() {
        return root;
    }

    private void putCached(String projectId, CacheEntry entry) {
        synchronized (cacheLock) {
            CacheEntry previous = cache.remove(projectId);
            if (previous != null) cacheWeightBytes -= previous.weightBytes();
            if (entry.weightBytes() > maxCacheWeightBytes) return;
            cache.put(projectId, entry);
            cacheWeightBytes = safeAdd(cacheWeightBytes, entry.weightBytes());
            while (cacheWeightBytes > maxCacheWeightBytes && !cache.isEmpty()) {
                String eldest = cache.keySet().iterator().next();
                CacheEntry removed = cache.remove(eldest);
                cacheWeightBytes -= removed.weightBytes();
                cacheEvictions++;
            }
        }
    }

    private void removeCached(String projectId, boolean eviction) {
        synchronized (cacheLock) {
            CacheEntry removed = cache.remove(projectId);
            if (removed != null) {
                cacheWeightBytes -= removed.weightBytes();
                if (eviction) cacheEvictions++;
            }
        }
    }

    private Path readableIndexFile(String projectId) {
        Path directory = projectDirectory(projectId);
        Path current = directory.resolve(CURRENT_FILE);
        if (Files.isRegularFile(current)) return current;
        Path legacy = directory.resolve(LEGACY_FILE);
        return Files.isRegularFile(legacy) ? legacy : null;
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

    private static String readString(DataInputStream input, DecodeBudget budget) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_STRING_BYTES) throw new IOException("invalid semantic index string length");
        budget.accountString(length);
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new EOFException("truncated semantic index string");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void requireIndexFileSize(Path file) throws IOException {
        long size = Files.size(file);
        if (size < 1L || size > MAX_INDEX_FILE_BYTES) {
            throw new IOException("semantic index exceeds persisted byte limit: " + size + "/" + MAX_INDEX_FILE_BYTES);
        }
    }

    private static final class DecodeBudget {
        private long stringBytes;
        private long vectorBytes;

        void accountString(long bytes) throws IOException {
            stringBytes = checkedAdd(stringBytes, bytes, "string");
            if (stringBytes > MAX_DECODED_STRING_BYTES) {
                throw new IOException("semantic index decoded strings exceed heap budget");
            }
        }

        void accountVector(int dimensions) throws IOException {
            long bytes;
            try { bytes = Math.multiplyExact((long) dimensions, Double.BYTES); }
            catch (ArithmeticException exception) { throw new IOException("semantic vector byte counter overflow", exception); }
            vectorBytes = checkedAdd(vectorBytes, bytes, "vector");
            if (vectorBytes > MAX_DECODED_VECTOR_BYTES) {
                throw new IOException("semantic index decoded vectors exceed heap budget");
            }
        }

        private static long checkedAdd(long left, long right, String label) throws IOException {
            try { return Math.addExact(left, right); }
            catch (ArithmeticException exception) { throw new IOException("semantic " + label + " byte counter overflow", exception); }
        }
    }

    private static String nullable(String value) {
        return value.isEmpty() ? null : value;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }

    private static long estimateWeight(IndexSnapshot snapshot) {
        long weight = 1024L;
        for (IndexedDocument indexed : snapshot.documents()) {
            SemanticDocument document = indexed.document();
            weight = safeAdd(weight, 256L);
            weight = safeAdd(weight, (long) indexed.vector().dimensions() * Double.BYTES);
            weight = safeAdd(weight, stringWeight(document.id()));
            weight = safeAdd(weight, stringWeight(document.stableKey()));
            weight = safeAdd(weight, stringWeight(document.sourceId()));
            weight = safeAdd(weight, stringWeight(document.fileId()));
            weight = safeAdd(weight, stringWeight(document.content()));
            weight = safeAdd(weight, stringWeight(document.checksum()));
        }
        return weight;
    }

    private static long stringWeight(String value) {
        return value == null ? 0L : safeAdd(40L, (long) value.length() * Character.BYTES);
    }

    private static long safeAdd(long left, long right) {
        if (right > Long.MAX_VALUE - left) return Long.MAX_VALUE;
        return left + right;
    }

    private record CacheEntry(IndexSnapshot snapshot, Path file, long sizeBytes, long lastModifiedMillis, long weightBytes) {
        static CacheEntry capture(Path file, IndexSnapshot snapshot) throws IOException {
            return new CacheEntry(snapshot, file, Files.size(file), Files.getLastModifiedTime(file).toMillis(), estimateWeight(snapshot));
        }

        boolean matches(Path candidate) throws IOException {
            return file.equals(candidate)
                    && sizeBytes == Files.size(candidate)
                    && lastModifiedMillis == Files.getLastModifiedTime(candidate).toMillis();
        }
    }

    public record CacheStats(
            long hits,
            long misses,
            long evictions,
            int entries,
            long weightBytes,
            long maximumWeightBytes
    ) {
    }
}
