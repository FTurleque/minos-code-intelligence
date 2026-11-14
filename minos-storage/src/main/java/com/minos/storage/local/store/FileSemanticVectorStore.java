package com.minos.storage.local.store;

import com.minos.io.PrivateLocalStorage;
import com.minos.io.ConfinedFileOpener;
import com.minos.io.BoundedFileLease;
import com.minos.io.DurableAtomicFile;
import com.minos.semantic.SemanticDocument;
import com.minos.semantic.SemanticDocumentKind;
import com.minos.semantic.SemanticVector;
import com.minos.semantic.SemanticVectorStore;
import com.minos.semantic.StaleSemanticSyncException;
import com.minos.store.cache.WeightedLruCache;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

import static com.minos.domain.Preconditions.requireText;
import static com.minos.diagnostics.PublicErrorMessages.leafName;

/** Local versioned vector store. Rebuild from active snapshots is always authoritative. */
public final class FileSemanticVectorStore implements SemanticVectorStore {

    private static final Object JVM_SYNC_LOCK_MONITOR = new Object();
    private static final Map<Path, SyncLockState> JVM_SYNC_LOCKS = new HashMap<>();
    private static final Duration SYNC_LOCK_TIMEOUT = Duration.ofSeconds(10);

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
    private final WeightedLruCache<String, CacheEntry> cache;
    private long cacheHits;
    private long cacheMisses;
    private long invalidationEvictions;
    private final Map<String, ResumePoint> resumePoints = new HashMap<>();

    public FileSemanticVectorStore(Path root) throws IOException {
        this(root, DEFAULT_MAX_CACHE_WEIGHT_BYTES);
    }

    FileSemanticVectorStore(Path root, long maxCacheWeightBytes) throws IOException {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        if (maxCacheWeightBytes < 1L) throw new IllegalArgumentException("maxCacheWeightBytes must be positive");
        this.maxCacheWeightBytes = maxCacheWeightBytes;
        this.cache = new WeightedLruCache<>(Integer.MAX_VALUE, maxCacheWeightBytes);
        DurableAtomicFile.ensureDirectory(this.root, "semantic index root");
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
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(
                ConfinedFileOpener.openRegularFileNoFollow(file)))) {
            if (input.readInt() != MAGIC) throw new IOException("invalid semantic index magic: " + leafName(file));
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
            throw new IOException("truncated semantic index: " + leafName(file), exception);
        }
    }

    @Override
    public Optional<IndexMetadata> metadata(String projectId) throws IOException {
        requireText(projectId, "projectId");
        Path file = readableIndexFile(projectId);
        if (file == null) return Optional.empty();
        requireIndexFileSize(file);
        DecodeBudget budget = new DecodeBudget();
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(
                ConfinedFileOpener.openRegularFileNoFollow(file)))) {
            if (input.readInt() != MAGIC) throw new IOException("invalid semantic index magic: " + leafName(file));
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
            throw new IOException("truncated semantic index metadata: " + leafName(file), exception);
        }
    }

    /**
     * Stable key and checksum of every document, read by walking the file: contents and vectors are skipped,
     * neither decoded nor kept, and the index cache is not used.
     */
    @Override
    public Optional<IndexChecksums> checksums(String projectId) throws IOException {
        requireText(projectId, "projectId");
        Path file = readableIndexFile(projectId);
        if (file == null) return Optional.empty();
        requireIndexFileSize(file);
        try (IndexReader reader = IndexReader.open(file, 0L)) {
            IndexHeader header = reader.header(projectId);
            Map<String, String> checksums = new HashMap<>();
            for (int index = 0; index < header.count(); index++) {
                reader.skipString();
                String stableKey = reader.string();
                reader.skipString();
                reader.skipDocumentTail();
                String checksum = reader.string();
                reader.skipVector(header);
                if (checksums.put(stableKey, checksum) != null) {
                    throw new IOException("semantic index holds a stable key twice: " + leafName(file));
                }
            }
            reader.requireEnd();
            return Optional.of(new IndexChecksums(header.metadata(), checksums));
        } catch (EOFException exception) {
            throw new IOException("truncated semantic index: " + leafName(file), exception);
        }
    }

    /**
     * One page of the stored vectors, read by walking the file from the place the previous page stopped at:
     * only the vectors of the page are decoded, no content is, and the index cache is not used. The file
     * holds its documents in stable-key order; a file that does not is refused. An index in the legacy
     * format, whose order is not guaranteed, is read through the full load.
     */
    @Override
    public Optional<VectorPage> vectorPage(String projectId, String afterStableKey, int limit) throws IOException {
        SemanticVectorStore.requireVectorPageBounds(afterStableKey, limit);
        requireText(projectId, "projectId");
        Path file = readableIndexFile(projectId);
        if (file == null) return Optional.empty();
        requireIndexFileSize(file);
        if (formatVersion(projectId) == LEGACY_FORMAT_VERSION) {
            return SemanticVectorStore.super.vectorPage(projectId, afterStableKey, limit);
        }
        FileStamp stamp = FileStamp.of(file);
        Optional<ResumePoint> resume = resumePoint(projectId, stamp, afterStableKey);
        try (IndexReader reader = IndexReader.open(file, resume.map(ResumePoint::offset).orElse(0L))) {
            if (!stamp.equals(FileStamp.of(file))) {
                throw new IOException("semantic index was replaced while it was read; read it again");
            }
            VectorWalk walk = resume.isPresent()
                    ? new VectorWalk(reader, resume.orElseThrow().header(), resume.orElseThrow().consumed(),
                            resume.orElseThrow().afterStableKey())
                    : new VectorWalk(reader, reader.header(projectId), 0, null);
            List<StoredVector> vectors = walk.read(afterStableKey, limit);
            rememberResumePoint(projectId, walk.hasMore() && !vectors.isEmpty()
                    ? Optional.of(new ResumePoint(stamp, vectors.getLast().stableKey(), reader.position(),
                            walk.consumed, walk.header))
                    : Optional.empty());
            return Optional.of(new VectorPage(walk.header.metadata(), vectors, walk.hasMore()));
        } catch (EOFException exception) {
            throw new IOException("truncated semantic index: " + leafName(file), exception);
        } catch (IllegalArgumentException exception) {
            throw new IOException("invalid semantic index entry: " + leafName(file), exception);
        }
    }

    /** The place the previous page of this very file stopped at, when the page asked for follows it. */
    private Optional<ResumePoint> resumePoint(String projectId, FileStamp stamp, String afterStableKey) {
        synchronized (resumePoints) {
            ResumePoint known = resumePoints.get(projectId);
            boolean follows = known != null && known.stamp().equals(stamp)
                    && known.afterStableKey().equals(afterStableKey);
            return follows ? Optional.of(known) : Optional.empty();
        }
    }

    private void rememberResumePoint(String projectId, Optional<ResumePoint> point) {
        synchronized (resumePoints) {
            if (point.isEmpty()) resumePoints.remove(projectId);
            else resumePoints.put(projectId, point.orElseThrow());
        }
    }

    /** The reading of the entries of an index file from one position, in stable-key order. */
    private static final class VectorWalk {
        private final IndexReader reader;
        private final IndexHeader header;
        private int consumed;
        private String previous;

        VectorWalk(IndexReader reader, IndexHeader header, int consumed, String previous) {
            this.reader = reader;
            this.header = header;
            this.consumed = consumed;
            this.previous = previous;
        }

        boolean hasMore() {
            return consumed < header.count();
        }

        /** Up to {@code limit} vectors whose stable key follows {@code afterStableKey}; the others are skipped. */
        List<StoredVector> read(String afterStableKey, int limit) throws IOException {
            List<StoredVector> vectors = new ArrayList<>(Math.min(limit, 1_024));
            while (hasMore() && vectors.size() < limit) {
                reader.skipString();
                String stableKey = reader.string();
                if (previous != null && previous.compareTo(stableKey) >= 0) {
                    throw new IOException("semantic index is not in stable-key order");
                }
                previous = stableKey;
                SemanticDocumentKind kind = SemanticDocumentKind.valueOf(reader.string());
                reader.skipDocumentTail();
                reader.skipString();
                consumed++;
                if (afterStableKey != null && stableKey.compareTo(afterStableKey) <= 0) {
                    reader.skipVector(header);
                } else {
                    vectors.add(new StoredVector(kind, SemanticVector.fromArray(stableKey, reader.vector(header))));
                }
            }
            return vectors;
        }
    }

    @Override
    public void replace(IndexSnapshot requested) throws IOException {
        Objects.requireNonNull(requested, "snapshot");
        // A document marked as reused keeps the vector this store holds for it; the file is rewritten whole.
        IndexSnapshot snapshot = requested.withStoredVectors(
                requested.reusesStoredVectors() ? load(requested.projectId()) : Optional.empty());
        if (snapshot.documents().size() > MAX_DOCUMENTS) throw new IOException("semantic index exceeds document limit");
        if (snapshot.dimensions() > MAX_DIMENSIONS) throw new IOException("semantic index exceeds dimension limit");
        Path directory = ensureProjectDirectory(snapshot.projectId());
        Path target = directory.resolve(CURRENT_FILE);
        Path temporary = PrivateLocalStorage.createPrivateTempFile(directory, "index-v2-", ".tmp");
        List<IndexedDocument> ordered = snapshot.documents().stream()
                .sorted(Comparator.comparing(value -> value.document().stableKey()))
                .toList();
        try {
            try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(PrivateLocalStorage.openPrivateOutputStream(temporary)))) {
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
            DurableAtomicFile.replace(temporary, target, "semantic index replacement");
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
        DurableAtomicFile.ensureDirectory(lockDirectory, "semantic sync lock directory");
        Path lockFile = lockDirectory.resolve(safeProjectDirectory(next.projectId()) + ".lock")
                .toAbsolutePath().normalize();
        if (!lockFile.startsWith(lockDirectory)) {
            throw new IOException("semantic sync lock file escapes store directory");
        }
        SyncLockState state = retainJvmSyncLock(lockFile);
        try (BoundedFileLease ignored = BoundedFileLease.acquire(
                lockFile, state.lock, SYNC_LOCK_TIMEOUT, "semantic sync lease: " + next.projectId())) {
            Optional<String> currentActive = activeSnapshotReader.read();
            String currentId = currentActive.orElse(null);
            if (!expectedActiveSnapshotId.equals(currentId)) {
                throw new StaleSemanticSyncException(
                        next.projectId(), expectedActiveSnapshotId,
                        currentId != null ? currentId : "absent");
            }
            replace(next);
        } finally {
            releaseJvmSyncLock(lockFile, state);
        }
    }

    @Override
    public void delete(String projectId) throws IOException {
        requireText(projectId, "projectId");
        removeCached(projectId, false);
        Path directory = existingProjectDirectory(projectId);
        if (directory == null) return;
        Files.deleteIfExists(directory.resolve(CURRENT_FILE));
        Files.deleteIfExists(directory.resolve(LEGACY_FILE));
    }

    public long sizeBytes(String projectId) throws IOException {
        Path file = readableIndexFile(projectId);
        if (file == null) return 0L;
        return requireIndexFileAttributes(file).size();
    }

    /** Returns 0 when absent, otherwise the on-disk semantic index format version. */
    public int formatVersion(String projectId) throws IOException {
        Path file = readableIndexFile(projectId);
        if (file == null) return 0;
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(
                ConfinedFileOpener.openRegularFileNoFollow(file)))) {
            if (input.readInt() != MAGIC) throw new IOException("invalid semantic index magic: " + leafName(file));
            return input.readInt();
        }
    }

    public CacheStats cacheStats() {
        synchronized (cacheLock) {
            return new CacheStats(cacheHits, cacheMisses, cache.evictions() + invalidationEvictions, cache.size(),
                    cache.weight(), maxCacheWeightBytes);
        }
    }

    public Path root() {
        return root;
    }

    private void putCached(String projectId, CacheEntry entry) {
        synchronized (cacheLock) {
            cache.put(projectId, entry, entry.weightBytes());
        }
    }

    private void removeCached(String projectId, boolean eviction) {
        synchronized (cacheLock) {
            if (cache.remove(projectId) != null && eviction) invalidationEvictions++;
        }
    }

    private Path readableIndexFile(String projectId) throws IOException {
        Path directory = existingProjectDirectory(projectId);
        if (directory == null) return null;
        Path current = directory.resolve(CURRENT_FILE);
        if (regularIndexFileExists(current)) return current;
        Path legacy = directory.resolve(LEGACY_FILE);
        return regularIndexFileExists(legacy) ? legacy : null;
    }

    private Path ensureProjectDirectory(String projectId) throws IOException {
        Path directory = projectDirectory(projectId);
        DurableAtomicFile.ensureDirectory(directory, "semantic project directory");
        return directory;
    }

    private Path existingProjectDirectory(String projectId) throws IOException {
        Path directory = projectDirectory(projectId);
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return null;
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("semantic project entry must be a non-symlink directory: " + leafName(directory));
        }
        return directory;
    }

    private Path projectDirectory(String projectId) {
        return root.resolve(safeProjectDirectory(projectId));
    }

    private static boolean regularIndexFileExists(Path file) throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return false;
        requireIndexFileAttributes(file);
        return true;
    }

    private static BasicFileAttributes requireIndexFileAttributes(Path file) throws IOException {
        if (Files.isSymbolicLink(file)) {
            throw new IOException("semantic index entry must not be a symbolic link: " + leafName(file));
        }
        BasicFileAttributes attributes = Files.readAttributes(
                file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) {
            throw new IOException("semantic index entry must be a regular file: " + leafName(file));
        }
        return attributes;
    }

    private static String safeProjectDirectory(String projectId) {
        requireText(projectId, "projectId");
        if (!projectId.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("projectId contains unsupported path characters");
        }
        return projectId;
    }

    private static SyncLockState retainJvmSyncLock(Path lockFile) {
        synchronized (JVM_SYNC_LOCK_MONITOR) {
            SyncLockState state = JVM_SYNC_LOCKS.computeIfAbsent(lockFile, ignored -> new SyncLockState());
            state.references++;
            return state;
        }
    }

    private static void releaseJvmSyncLock(Path lockFile, SyncLockState state) {
        synchronized (JVM_SYNC_LOCK_MONITOR) {
            state.references--;
            if (state.references < 0) throw new IllegalStateException("semantic JVM sync lock underflow");
            if (state.references == 0) JVM_SYNC_LOCKS.remove(lockFile, state);
        }
    }

    private static final class SyncLockState {
        private final ReentrantLock lock = new ReentrantLock();
        private int references;
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
        long size = requireIndexFileAttributes(file).size();
        if (size < 1L || size > MAX_INDEX_FILE_BYTES) {
            throw new IOException("semantic index exceeds persisted byte limit: " + size + "/" + MAX_INDEX_FILE_BYTES);
        }
    }

    /** The file a read was made on, told apart from its replacement by its size and its modification time. */
    private record FileStamp(Path file, long sizeBytes, long lastModifiedMillis) {
        static FileStamp of(Path file) throws IOException {
            BasicFileAttributes attributes = requireIndexFileAttributes(file);
            return new FileStamp(file, attributes.size(), attributes.lastModifiedTime().toMillis());
        }
    }

    /** Where the walk of a file stopped: the next page of the same file starts there without reading again. */
    private record ResumePoint(FileStamp stamp, String afterStableKey, long offset, int consumed, IndexHeader header) {
    }

    private record IndexHeader(int version, IndexMetadata metadata) {
        int count() {
            return metadata.documentCount();
        }

        long vectorBytes() {
            return (long) metadata.dimensions() * (version == LEGACY_FORMAT_VERSION ? Double.BYTES : Float.BYTES);
        }
    }

    /** Sequential reader of an index file that knows its position and skips what it is not asked for. */
    private static final class IndexReader implements Closeable {
        private final DataInputStream input;
        private final DecodeBudget budget = new DecodeBudget();
        private long position;

        private IndexReader(DataInputStream input, long position) {
            this.input = input;
            this.position = position;
        }

        static IndexReader open(Path file, long offset) throws IOException {
            SeekableByteChannel channel = ConfinedFileOpener.openRegularFileNoFollowChannel(file);
            try {
                channel.position(offset);
            } catch (IOException | RuntimeException failure) {
                channel.close();
                throw failure;
            }
            return new IndexReader(new DataInputStream(new BufferedInputStream(Channels.newInputStream(channel))),
                    offset);
        }

        long position() {
            return position;
        }

        IndexHeader header(String projectId) throws IOException {
            if (integer() != MAGIC) throw new IOException("invalid semantic index magic");
            int version = integer();
            if (version != LEGACY_FORMAT_VERSION && version != FORMAT_VERSION) {
                throw new IOException("unsupported semantic index version: " + version);
            }
            String storedProject = string();
            String snapshotId = string();
            String providerId = string();
            String modelId = string();
            int dimensions = integer();
            long builtAt = input.readLong();
            position += Long.BYTES;
            int count = integer();
            if (!projectId.equals(storedProject)) throw new IOException("semantic index project mismatch");
            if (dimensions < 1 || dimensions > MAX_DIMENSIONS || count < 0 || count > MAX_DOCUMENTS) {
                throw new IOException("invalid semantic index metadata");
            }
            return new IndexHeader(version,
                    new IndexMetadata(storedProject, snapshotId, providerId, modelId, dimensions, builtAt, count));
        }

        int integer() throws IOException {
            int value = input.readInt();
            position += Integer.BYTES;
            return value;
        }

        /** A string and its exact length in the file: the position never depends on how the bytes decode. */
        String string() throws IOException {
            int length = integer();
            if (length < 0 || length > MAX_STRING_BYTES) throw new IOException("invalid semantic index string length");
            budget.accountString(length);
            byte[] bytes = input.readNBytes(length);
            if (bytes.length != length) throw new EOFException("truncated semantic index string");
            position += length;
            return new String(bytes, StandardCharsets.UTF_8);
        }

        void skipString() throws IOException {
            int length = integer();
            if (length < 0 || length > MAX_STRING_BYTES) throw new IOException("invalid semantic index string length");
            skip(length);
        }

        /** Source, file, lines and content of a document: what lies between its kind and its checksum. */
        void skipDocumentTail() throws IOException {
            skipString();
            skipString();
            skip(2L * Integer.BYTES);
            skipString();
        }

        double[] vector(IndexHeader header) throws IOException {
            requireVectorSize(header);
            double[] values = new double[header.metadata().dimensions()];
            for (int index = 0; index < values.length; index++) {
                values[index] = header.version() == LEGACY_FORMAT_VERSION ? input.readDouble() : input.readFloat();
            }
            position += header.vectorBytes();
            return values;
        }

        void skipVector(IndexHeader header) throws IOException {
            requireVectorSize(header);
            skip(header.vectorBytes());
        }

        private void requireVectorSize(IndexHeader header) throws IOException {
            if (integer() != header.metadata().dimensions()) throw new IOException("semantic vector dimensions mismatch");
        }

        void requireEnd() throws IOException {
            if (input.read() != -1) throw new IOException("unexpected trailing semantic index data");
        }

        private void skip(long bytes) throws IOException {
            input.skipNBytes(bytes);
            position += bytes;
        }

        @Override
        public void close() throws IOException {
            input.close();
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

    private static long estimateWeight(IndexSnapshot snapshot) {
        long weight = 1024L;
        for (IndexedDocument indexed : snapshot.documents()) {
            SemanticDocument document = indexed.document();
            weight = saturatingAdd(weight, 256L);
            weight = saturatingAdd(weight, (long) indexed.vector().dimensions() * Double.BYTES);
            weight = saturatingAdd(weight, stringWeight(document.id()));
            weight = saturatingAdd(weight, stringWeight(document.stableKey()));
            weight = saturatingAdd(weight, stringWeight(document.sourceId()));
            weight = saturatingAdd(weight, stringWeight(document.fileId()));
            weight = saturatingAdd(weight, stringWeight(document.content()));
            weight = saturatingAdd(weight, stringWeight(document.checksum()));
        }
        return weight;
    }

    private static long stringWeight(String value) {
        return value == null ? 0L : saturatingAdd(40L, (long) value.length() * Character.BYTES);
    }

    private static long saturatingAdd(long left, long right) {
        if (right > Long.MAX_VALUE - left) return Long.MAX_VALUE;
        return left + right;
    }

    private record CacheEntry(IndexSnapshot snapshot, Path file, long sizeBytes, long lastModifiedMillis, long weightBytes) {
        static CacheEntry capture(Path file, IndexSnapshot snapshot) throws IOException {
            BasicFileAttributes attributes = requireIndexFileAttributes(file);
            return new CacheEntry(snapshot, file, attributes.size(), attributes.lastModifiedTime().toMillis(),
                    estimateWeight(snapshot));
        }

        boolean matches(Path candidate) throws IOException {
            if (!file.equals(candidate)) return false;
            BasicFileAttributes attributes = requireIndexFileAttributes(candidate);
            return sizeBytes == attributes.size()
                    && lastModifiedMillis == attributes.lastModifiedTime().toMillis();
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
