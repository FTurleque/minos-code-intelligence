package com.minos.storage.local.store;

import com.minos.io.BoundedFileLease;
import com.minos.io.ConfinedFileOpener;
import com.minos.io.DurableAtomicFile;
import com.minos.io.PrivateLocalStorage;
import com.minos.io.Sha256;
import com.minos.semantic.SemanticDocumentKind;
import com.minos.semantic.SemanticProjection;
import com.minos.semantic.SemanticProjectionStore;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import static com.minos.domain.Preconditions.requireText;

/**
 * Projection cache in one file per project, {@code <root>/<project>/projection-v1.bin}, the same for every
 * storage backend. The file ends with the SHA-256 of everything before it: a file that was cut, altered or
 * written by another version is refused when it is read. A projection is published by replacing the file in one
 * atomic step, from a temporary file of the same directory that is removed whatever happens.
 */
public final class FileSemanticProjectionStore implements SemanticProjectionStore {

    static final String FILE = "projection-v1.bin";
    static final String LOCK_FILE = "prepare.lock";

    private static final int MAGIC = 0x4D53504A; // MSPJ
    private static final int FORMAT_VERSION = 1;
    private static final int DIGEST_BYTES = 32;
    private static final int MAX_POINTS = 2_000_000;
    private static final int MAX_STRING_BYTES = 64 * 1024;
    private static final long MAX_FILE_BYTES = 512L * 1024L * 1024L;
    private static final Duration PREPARATION_LOCK_WAIT = Duration.ofMillis(200);

    private static final Object JVM_LOCK_MONITOR = new Object();
    private static final Map<Path, ReentrantLock> JVM_LOCKS = new HashMap<>();

    private final Path root;
    private final Object cacheLock = new Object();
    private final Map<String, CachedProjection> cache = new HashMap<>();

    /** Nothing is created here: a reader works on a root it cannot write, or that does not exist yet. */
    public FileSemanticProjectionStore(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    @Override
    public Optional<SemanticProjection> read(String projectId) throws IOException {
        Path file = projectDirectory(projectId).resolve(FILE);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            forget(projectId);
            return Optional.empty();
        }
        FileStamp stamp = FileStamp.of(file);
        synchronized (cacheLock) {
            CachedProjection known = cache.get(projectId);
            if (known != null && known.stamp().equals(stamp)) return Optional.of(known.projection());
        }
        SemanticProjection projection = decode(projectId, file, stamp.sizeBytes());
        synchronized (cacheLock) {
            cache.clear();
            cache.put(projectId, new CachedProjection(stamp, projection));
        }
        return Optional.of(projection);
    }

    @Override
    public Preparation prepare(String projectId) throws IOException {
        Path directory = projectDirectory(projectId);
        DurableAtomicFile.ensureDirectory(directory, "semantic projection directory");
        Path lockFile = directory.resolve(LOCK_FILE);
        ReentrantLock jvmLock = jvmLock(lockFile);
        BoundedFileLease lease;
        try {
            lease = BoundedFileLease.acquire(lockFile, jvmLock, PREPARATION_LOCK_WAIT, "semantic projection lease");
        } catch (IOException busy) {
            throw new PreparationInProgressException(busy);
        }
        return new FilePreparation(projectId, directory, lease);
    }

    private final class FilePreparation implements Preparation {
        private final String projectId;
        private final Path directory;
        private final BoundedFileLease lease;

        FilePreparation(String projectId, Path directory, BoundedFileLease lease) {
            this.projectId = projectId;
            this.directory = directory;
            this.lease = lease;
        }

        @Override
        public void publish(SemanticProjection projection) throws IOException {
            Objects.requireNonNull(projection, "projection");
            if (!projectId.equals(projection.index().projectId())) {
                throw new IllegalArgumentException("projection belongs to another project");
            }
            if (projection.points().size() > MAX_POINTS) throw new IOException("projection exceeds point limit");
            Path temporary = PrivateLocalStorage.createPrivateTempFile(directory, "projection-v1-", ".tmp");
            try {
                encode(projection, temporary);
                DurableAtomicFile.replace(temporary, directory.resolve(FILE), "semantic projection replacement");
                forget(projectId);
            } finally {
                Files.deleteIfExists(temporary);
            }
        }

        @Override
        public void close() throws IOException {
            lease.close();
        }
    }

    private static void encode(SemanticProjection projection, Path file) throws IOException {
        MessageDigest digest = Sha256.newDigest();
        try (DigestOutputStream digested = new DigestOutputStream(
                new BufferedOutputStream(PrivateLocalStorage.openPrivateOutputStream(file)), digest);
             DataOutputStream output = new DataOutputStream(digested)) {
            output.writeInt(MAGIC);
            output.writeInt(FORMAT_VERSION);
            SemanticProjection.IndexIdentity index = projection.index();
            writeString(output, index.projectId());
            writeString(output, index.snapshotId());
            writeString(output, index.providerId());
            writeString(output, index.modelId());
            output.writeInt(index.dimensions());
            writeString(output, index.documentsFingerprint());
            writeString(output, projection.method().name());
            output.writeInt(projection.method().version());
            writeString(output, projection.method().parameters());
            output.writeLong(projection.method().seed());
            output.writeInt(projection.selection().kinds().size());
            for (SemanticDocumentKind kind : projection.selection().kinds()) writeString(output, kind.name());
            output.writeInt(projection.selection().maxPoints());
            output.writeInt(projection.documentCount());
            output.writeInt(projection.candidateCount());
            for (double share : projection.explainedVariance()) output.writeDouble(share);
            output.writeInt(projection.points().size());
            for (SemanticProjection.Point point : projection.points()) {
                writeString(output, point.stableKey());
                writeString(output, point.kind().name());
                output.writeFloat(point.x());
                output.writeFloat(point.y());
            }
            output.flush();
            digested.on(false);
            output.write(digest.digest());
        }
    }

    private static SemanticProjection decode(String projectId, Path file, long sizeBytes) throws IOException {
        if (sizeBytes <= DIGEST_BYTES || sizeBytes > MAX_FILE_BYTES) throw unreadable(null);
        MessageDigest digest = Sha256.newDigest();
        try (InputStream raw = new BufferedInputStream(ConfinedFileOpener.openRegularFileNoFollow(file));
             DigestInputStream digested = new DigestInputStream(raw, digest);
             DataInputStream input = new DataInputStream(digested)) {
            if (input.readInt() != MAGIC || input.readInt() != FORMAT_VERSION) throw unreadable(null);
            SemanticProjection.IndexIdentity index = new SemanticProjection.IndexIdentity(
                    readString(input), readString(input), readString(input), readString(input),
                    input.readInt(), readString(input));
            if (!projectId.equals(index.projectId())) throw unreadable(null);
            SemanticProjection.Method method = new SemanticProjection.Method(
                    readString(input), input.readInt(), readString(input), input.readLong());
            SemanticProjection.Selection selection = readSelection(input);
            int documentCount = input.readInt();
            int candidateCount = input.readInt();
            List<Double> variance = List.of(input.readDouble(), input.readDouble());
            List<SemanticProjection.Point> points = readPoints(input);
            digested.on(false);
            byte[] expected = digest.digest();
            byte[] stored = input.readNBytes(DIGEST_BYTES);
            if (!MessageDigest.isEqual(expected, stored) || input.read() != -1) throw unreadable(null);
            return new SemanticProjection(index, method, selection, documentCount, candidateCount, variance, points);
        } catch (EOFException | IllegalArgumentException malformed) {
            throw unreadable(malformed);
        }
    }

    private static SemanticProjection.Selection readSelection(DataInputStream input) throws IOException {
        int kindCount = input.readInt();
        if (kindCount < 1 || kindCount > SemanticDocumentKind.values().length) throw unreadable(null);
        Set<SemanticDocumentKind> kinds = EnumSet.noneOf(SemanticDocumentKind.class);
        for (int position = 0; position < kindCount; position++) {
            kinds.add(SemanticDocumentKind.valueOf(readString(input)));
        }
        return new SemanticProjection.Selection(kinds, input.readInt());
    }

    private static List<SemanticProjection.Point> readPoints(DataInputStream input) throws IOException {
        int pointCount = input.readInt();
        if (pointCount < 0 || pointCount > MAX_POINTS) throw unreadable(null);
        List<SemanticProjection.Point> points = new ArrayList<>(Math.min(pointCount, 16_384));
        for (int position = 0; position < pointCount; position++) {
            points.add(new SemanticProjection.Point(readString(input),
                    SemanticDocumentKind.valueOf(readString(input)), input.readFloat(), input.readFloat()));
        }
        return points;
    }

    private static UnreadableProjectionException unreadable(Exception cause) {
        String message = "the stored projection is unreadable; prepare it again";
        return cause == null ? new UnreadableProjectionException(message)
                : new UnreadableProjectionException(message, cause);
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) throw new IOException("projection string exceeds safety limit");
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_STRING_BYTES) throw unreadable(null);
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new EOFException("truncated projection string");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private void forget(String projectId) {
        synchronized (cacheLock) {
            cache.remove(projectId);
        }
    }

    private Path projectDirectory(String projectId) {
        requireText(projectId, "projectId");
        if (!projectId.matches("[A-Za-z0-9._-]+") || projectId.startsWith(".")) {
            throw new IllegalArgumentException("projectId contains unsupported path characters");
        }
        return root.resolve(projectId);
    }

    private static ReentrantLock jvmLock(Path lockFile) {
        synchronized (JVM_LOCK_MONITOR) {
            return JVM_LOCKS.computeIfAbsent(lockFile, ignored -> new ReentrantLock());
        }
    }

    /** The file a projection was read from, told apart from its replacement by its size and its time. */
    private record FileStamp(long sizeBytes, long lastModifiedMillis) {
        static FileStamp of(Path file) throws IOException {
            BasicFileAttributes attributes =
                    Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()) throw unreadable(null);
            return new FileStamp(attributes.size(), attributes.lastModifiedTime().toMillis());
        }
    }

    private record CachedProjection(FileStamp stamp, SemanticProjection projection) {
    }
}
