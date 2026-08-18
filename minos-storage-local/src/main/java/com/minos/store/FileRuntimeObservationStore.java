package com.minos.store;

import com.minos.dynamic.CorrelatedRuntimeObservation;
import com.minos.dynamic.CorrelatedRuntimeSession;
import com.minos.dynamic.RuntimeObservation;
import com.minos.dynamic.RuntimeObservationCompleteness;
import com.minos.dynamic.RuntimeObservationSession;
import com.minos.dynamic.RuntimeObservationStore;
import com.minos.dynamic.RuntimeObservationType;
import com.minos.dynamic.RuntimeResolutionStatus;
import com.minos.dynamic.RuntimeSymbolReference;
import com.minos.dynamic.RuntimeSymbolResolution;
import com.minos.io.BoundedFileLease;
import com.minos.io.DurableAtomicFile;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/** Atomic, bounded and checksum-verified local persistence for M26 runtime sessions. */
public final class FileRuntimeObservationStore implements RuntimeObservationStore {

    public static final int DEFAULT_MAX_SESSIONS_PER_PROJECT = 128;
    public static final long DEFAULT_MAX_PROJECT_BYTES = 1024L * 1024L * 1024L;
    public static final long DEFAULT_MAX_SESSION_BYTES = 64L * 1024L * 1024L;

    private static final int MAGIC = 0x4D525431;
    private static final int VERSION = 1;
    private static final int MAX_STRING_BYTES = 128 * 1024;
    private static final int MAX_CANDIDATES = RuntimeSymbolResolution.MAX_CANDIDATE_SYMBOL_IDS;
    private static final String EXTENSION = ".mrt";
    private static final int LOCK_STRIPES = 64;
    private static final Duration LOCK_TIMEOUT = Duration.ofSeconds(10);
    private static final ReentrantLock[] JVM_LOCKS = locks();

    private final Path root;
    private final int maxSessionsPerProject;
    private final long maxProjectBytes;
    private final long maxSessionBytes;

    public FileRuntimeObservationStore(Path root) throws IOException {
        this(root, DEFAULT_MAX_SESSIONS_PER_PROJECT, DEFAULT_MAX_PROJECT_BYTES, DEFAULT_MAX_SESSION_BYTES);
    }

    public FileRuntimeObservationStore(
            Path root,
            int maxSessionsPerProject,
            long maxProjectBytes,
            long maxSessionBytes
    ) throws IOException {
        if (maxSessionsPerProject < 1) throw new IllegalArgumentException("maxSessionsPerProject must be positive");
        if (maxProjectBytes < 1 || maxSessionBytes < 1 || maxSessionBytes > maxProjectBytes) {
            throw new IllegalArgumentException("runtime observation storage byte limits are invalid");
        }
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.maxSessionsPerProject = maxSessionsPerProject;
        this.maxProjectBytes = maxProjectBytes;
        this.maxSessionBytes = maxSessionBytes;
        DurableAtomicFile.ensureDirectory(this.root, "runtime observation root");
    }

    @Override
    public SaveResult save(CorrelatedRuntimeSession session) throws IOException {
        Objects.requireNonNull(session, "session");
        UUID projectId = session.session().projectId();
        Path project = projectDirectory(projectId);
        rejectUnsafeProjectEntry(project);
        DurableAtomicFile.ensureDirectory(project, "runtime observation project directory");
        requireProjectDirectory(project);
        try (LockedProject ignored = lock(project)) {
            Optional<CorrelatedRuntimeSession> existing = findUnlocked(project, projectId, session.session().sessionId());
            if (existing.isPresent()) {
                CorrelatedRuntimeSession stored = existing.orElseThrow();
                if (!stored.sourceSha256().equals(session.sourceSha256())) {
                    throw new IOException("runtime session is immutable and already exists with different content: "
                            + session.session().sessionId());
                }
                return new SaveResult(stored, true);
            }

            List<Path> current = sessionFiles(project);
            if (current.size() >= maxSessionsPerProject) {
                throw new IOException("runtime observation session capacity reached for project " + projectId);
            }
            long currentBytes = totalBytes(current);
            Path temporary = Files.createTempFile(project, ".runtime-session-", ".tmp");
            try {
                write(temporary, session);
                long size = Files.size(temporary);
                if (size > maxSessionBytes) throw new IOException("encoded runtime session exceeds byte limit");
                if (currentBytes > maxProjectBytes - size) {
                    throw new IOException("runtime observation storage byte capacity reached for project " + projectId);
                }
                String contentSha = sha256(temporary);
                String idHash = digest(session.session().sessionId().getBytes(StandardCharsets.UTF_8));
                Path target = project.resolve("session-" + idHash + "-" + contentSha + EXTENSION);
                moveAtomically(temporary, target);
                return new SaveResult(session, false);
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
    }

    @Override
    public Optional<CorrelatedRuntimeSession> find(UUID projectId, String sessionId) throws IOException {
        Objects.requireNonNull(projectId, "projectId");
        requireText(sessionId, "sessionId");
        Path project = projectDirectory(projectId);
        rejectUnsafeProjectEntry(project);
        if (!Files.exists(project, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        requireProjectDirectory(project);
        try (LockedProject ignored = lock(project)) {
            return findUnlocked(project, projectId, sessionId);
        }
    }

    @Override
    public List<CorrelatedRuntimeSession> list(UUID projectId) throws IOException {
        return list(projectId, null, maxSessionsPerProject);
    }

    @Override
    public List<CorrelatedRuntimeSession> list(UUID projectId, String snapshotId, int limit) throws IOException {
        Objects.requireNonNull(projectId, "projectId");
        if (limit < 1) throw new IllegalArgumentException("runtime session limit must be positive");
        Path project = projectDirectory(projectId);
        rejectUnsafeProjectEntry(project);
        if (!Files.exists(project, LinkOption.NOFOLLOW_LINKS)) return List.of();
        requireProjectDirectory(project);
        try (LockedProject ignored = lock(project)) {
            List<SessionMetadata> metadata = new ArrayList<>();
            for (Path file : sessionFiles(project)) {
                SessionMetadata candidate = readMetadata(file, projectId);
                if (snapshotId == null || snapshotId.equals(candidate.snapshotId())) metadata.add(candidate);
            }
            metadata.sort(Comparator.comparing(SessionMetadata::importedAt).reversed()
                    .thenComparing(SessionMetadata::sessionId));
            List<CorrelatedRuntimeSession> sessions = new ArrayList<>(Math.min(limit, metadata.size()));
            for (SessionMetadata candidate : metadata) {
                if (sessions.size() >= limit) break;
                sessions.add(readVerified(candidate.file(), projectId));
            }
            return List.copyOf(sessions);
        }
    }

    public Path root() {
        return root;
    }

    private SessionMetadata readMetadata(Path file, UUID expectedProjectId) throws IOException {
        requireRegularSessionFile(file);
        long size = Files.size(file);
        if (size < 1L || size > maxSessionBytes) throw new IOException("persisted runtime session size is invalid");
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(
                Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)))) {
            if (input.readInt() != MAGIC) throw new IOException("invalid runtime session magic");
            int version = input.readInt();
            if (version != VERSION) throw new IOException("unsupported runtime session version: " + version);
            readString(input, "format");
            String sessionId = readString(input, "sessionId");
            UUID projectId = parseUuid(readString(input, "projectId"));
            if (!expectedProjectId.equals(projectId)) throw new IOException("runtime session project identity mismatch");
            String snapshotId = readString(input, "snapshotId");
            readInstant(input);
            readInstant(input);
            readString(input, "collectorId");
            readString(input, "collectorVersion");
            readString(input, "environment");
            readString(input, "completeness");
            Instant importedAt = readInstant(input);
            readString(input, "sourceSha256");
            return new SessionMetadata(file, snapshotId, sessionId, importedAt);
        } catch (EOFException exception) {
            throw new IOException("truncated runtime session metadata", exception);
        }
    }

    private Optional<CorrelatedRuntimeSession> findUnlocked(Path project, UUID projectId, String sessionId) throws IOException {
        String idHash = digest(sessionId.getBytes(StandardCharsets.UTF_8));
        List<Path> matches = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(project, "session-" + idHash + "-*" + EXTENSION)) {
            for (Path path : stream) matches.add(path);
        }
        if (matches.size() > 1) throw new IOException("duplicate persisted runtime session identity: " + sessionId);
        if (matches.isEmpty()) return Optional.empty();
        CorrelatedRuntimeSession session = readVerified(matches.getFirst(), projectId);
        if (!sessionId.equals(session.session().sessionId())) throw new IOException("runtime session filename identity mismatch");
        return Optional.of(session);
    }

    private CorrelatedRuntimeSession readVerified(Path file, UUID expectedProjectId) throws IOException {
        requireRegularSessionFile(file);
        long size = Files.size(file);
        if (size < 1 || size > maxSessionBytes) throw new IOException("persisted runtime session size is invalid");
        String name = file.getFileName().toString();
        int separator = name.lastIndexOf('-');
        if (!name.startsWith("session-") || separator < 0 || !name.endsWith(EXTENSION)) {
            throw new IOException("invalid runtime session filename: " + name);
        }
        String expectedHash = name.substring(separator + 1, name.length() - EXTENSION.length());
        if (!expectedHash.matches("[0-9a-f]{64}") || !expectedHash.equals(sha256(file))) {
            throw new IOException("runtime session checksum mismatch: " + name);
        }
        CorrelatedRuntimeSession session = read(file);
        if (!expectedProjectId.equals(session.session().projectId())) {
            throw new IOException("runtime session project identity mismatch");
        }
        return session;
    }

    private static void write(Path file, CorrelatedRuntimeSession correlated) throws IOException {
        RuntimeObservationSession session = correlated.session();
        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))) {
            output.writeInt(MAGIC);
            output.writeInt(VERSION);
            writeString(output, session.format());
            writeString(output, session.sessionId());
            writeString(output, session.projectId().toString());
            writeString(output, session.snapshotId());
            writeInstant(output, session.startedAt());
            writeInstant(output, session.endedAt());
            writeString(output, session.collectorId());
            writeString(output, session.collectorVersion());
            writeString(output, session.environment());
            writeString(output, session.completeness().name());
            writeInstant(output, correlated.importedAt());
            writeString(output, correlated.sourceSha256());
            output.writeInt(correlated.observations().size());
            for (CorrelatedRuntimeObservation value : correlated.observations()) writeObservation(output, value);
        }
    }

    private static CorrelatedRuntimeSession read(Path file) throws IOException {
        requireRegularSessionFile(file);
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(
                Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)))) {
            if (input.readInt() != MAGIC) throw new IOException("invalid runtime session magic");
            int version = input.readInt();
            if (version != VERSION) throw new IOException("unsupported runtime session version: " + version);
            String format = readString(input, "format");
            String sessionId = readString(input, "sessionId");
            UUID projectId = parseUuid(readString(input, "projectId"));
            String snapshotId = readString(input, "snapshotId");
            Instant startedAt = readInstant(input);
            Instant endedAt = readInstant(input);
            String collectorId = readString(input, "collectorId");
            String collectorVersion = readString(input, "collectorVersion");
            String environment = readString(input, "environment");
            RuntimeObservationCompleteness completeness = parseEnum(
                    RuntimeObservationCompleteness.class, readString(input, "completeness"), "completeness");
            Instant importedAt = readInstant(input);
            String sourceSha = readString(input, "sourceSha256");
            int count = readCount(input, RuntimeObservationSession.MAX_OBSERVATIONS, "observation count");
            List<RuntimeObservation> raw = new ArrayList<>(count);
            List<CorrelatedRuntimeObservation> observations = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                CorrelatedRuntimeObservation observation = readObservation(input);
                raw.add(observation.observation());
                observations.add(observation);
            }
            if (input.read() != -1) throw new IOException("unexpected trailing runtime session data");
            RuntimeObservationSession session = new RuntimeObservationSession(
                    format, sessionId, projectId, snapshotId, startedAt, endedAt,
                    collectorId, collectorVersion, environment, completeness, raw);
            return new CorrelatedRuntimeSession(session, importedAt, sourceSha, observations);
        } catch (EOFException exception) {
            throw new IOException("truncated runtime session", exception);
        } catch (IllegalArgumentException exception) {
            throw new IOException("invalid persisted runtime session: " + exception.getMessage(), exception);
        }
    }

    private static void writeObservation(DataOutputStream output, CorrelatedRuntimeObservation value) throws IOException {
        RuntimeObservation observation = value.observation();
        writeString(output, observation.type().name());
        writeReference(output, observation.source());
        output.writeBoolean(observation.target() != null);
        if (observation.target() != null) writeReference(output, observation.target());
        output.writeLong(observation.hits());
        output.writeLong(observation.totalDurationNanos());
        writeResolution(output, value.source());
        output.writeBoolean(value.target() != null);
        if (value.target() != null) writeResolution(output, value.target());
    }

    private static CorrelatedRuntimeObservation readObservation(DataInputStream input) throws IOException {
        RuntimeObservationType type = parseEnum(RuntimeObservationType.class, readString(input, "observation type"), "observation type");
        RuntimeSymbolReference sourceRef = readReference(input);
        RuntimeSymbolReference targetRef = input.readBoolean() ? readReference(input) : null;
        RuntimeObservation observation = new RuntimeObservation(type, sourceRef, targetRef, input.readLong(), input.readLong());
        RuntimeSymbolResolution source = readResolution(input);
        RuntimeSymbolResolution target = input.readBoolean() ? readResolution(input) : null;
        return new CorrelatedRuntimeObservation(observation, source, target);
    }

    private static void writeReference(DataOutputStream output, RuntimeSymbolReference value) throws IOException {
        writeNullableString(output, value.symbolKey());
        writeNullableString(output, value.qualifiedName());
        writeNullableString(output, value.fileId());
        output.writeBoolean(value.line() != null);
        if (value.line() != null) output.writeInt(value.line());
    }

    private static RuntimeSymbolReference readReference(DataInputStream input) throws IOException {
        String key = readNullableString(input);
        String qualified = readNullableString(input);
        String file = readNullableString(input);
        Integer line = input.readBoolean() ? input.readInt() : null;
        return new RuntimeSymbolReference(key, qualified, file, line);
    }

    private static void writeResolution(DataOutputStream output, RuntimeSymbolResolution value) throws IOException {
        writeString(output, value.status().name());
        writeReference(output, value.reference());
        writeNullableString(output, value.symbolId());
        writeNullableString(output, value.symbolKey());
        writeNullableString(output, value.qualifiedName());
        output.writeInt(value.candidateSymbolIds().size());
        for (String candidate : value.candidateSymbolIds()) writeString(output, candidate);
        output.writeBoolean(value.candidatesTruncated());
    }

    private static RuntimeSymbolResolution readResolution(DataInputStream input) throws IOException {
        RuntimeResolutionStatus status = parseEnum(RuntimeResolutionStatus.class, readString(input, "resolution status"), "resolution status");
        RuntimeSymbolReference reference = readReference(input);
        String symbolId = readNullableString(input);
        String symbolKey = readNullableString(input);
        String qualifiedName = readNullableString(input);
        int count = readCount(input, MAX_CANDIDATES, "candidate count");
        List<String> candidates = new ArrayList<>(count);
        for (int index = 0; index < count; index++) candidates.add(readString(input, "candidate"));
        boolean truncated = input.readBoolean();
        return new RuntimeSymbolResolution(status, reference, symbolId, symbolKey, qualifiedName, candidates, truncated);
    }

    private static void writeInstant(DataOutputStream output, Instant value) throws IOException {
        output.writeLong(value.getEpochSecond());
        output.writeInt(value.getNano());
    }

    private static Instant readInstant(DataInputStream input) throws IOException {
        try {
            return Instant.ofEpochSecond(input.readLong(), input.readInt());
        } catch (RuntimeException exception) {
            throw new IOException("invalid runtime session instant", exception);
        }
    }

    private static void writeNullableString(DataOutputStream output, String value) throws IOException {
        output.writeBoolean(value != null);
        if (value != null) writeString(output, value);
    }

    private static String readNullableString(DataInputStream input) throws IOException {
        return input.readBoolean() ? readString(input, "string") : null;
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = Objects.requireNonNull(value, "value").getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 1 || bytes.length > MAX_STRING_BYTES) throw new IOException("runtime session string exceeds limit");
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input, String field) throws IOException {
        int length = input.readInt();
        if (length < 1 || length > MAX_STRING_BYTES) throw new IOException("invalid " + field + " byte length");
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new EOFException("truncated " + field);
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static int readCount(DataInputStream input, int maximum, String field) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > maximum) throw new IOException("invalid " + field + ": " + count);
        return count;
    }

    private static UUID parseUuid(String value) throws IOException {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new IOException("invalid persisted project UUID", exception);
        }
    }

    private static <T extends Enum<T>> T parseEnum(Class<T> type, String value, String field) throws IOException {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw new IOException("invalid " + field + ": " + value, exception);
        }
    }

    private List<Path> sessionFiles(Path project) throws IOException {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(project, "session-*" + EXTENSION)) {
            for (Path path : stream) {
                requireRegularSessionFile(path);
                files.add(path);
            }
        }
        files.sort(Comparator.comparing(path -> path.getFileName().toString()));
        if (files.size() > maxSessionsPerProject) throw new IOException("runtime observation storage exceeds session bound");
        if (totalBytes(files) > maxProjectBytes) throw new IOException("runtime observation storage exceeds byte bound");
        return files;
    }

    private static long totalBytes(List<Path> files) throws IOException {
        long total = 0;
        try {
            for (Path file : files) total = Math.addExact(total, Files.size(file));
        } catch (ArithmeticException exception) {
            throw new IOException("runtime observation storage size exceeds supported range", exception);
        }
        return total;
    }

    private Path projectDirectory(UUID projectId) {
        Path result = root.resolve(projectId.toString()).normalize();
        if (!result.getParent().equals(root)) throw new IllegalStateException("runtime project path escaped storage root");
        return result;
    }

    private static LockedProject lock(Path project) throws IOException {
        Path lockFile = project.resolve(".lock").toAbsolutePath().normalize();
        ReentrantLock jvmLock = JVM_LOCKS[Math.floorMod(lockFile.hashCode(), JVM_LOCKS.length)];
        return new LockedProject(BoundedFileLease.acquire(
                lockFile, jvmLock, LOCK_TIMEOUT, "runtime observation project lease"));
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException("atomic runtime session publication is not supported", exception);
        }
    }

    private static String sha256(Path file) throws IOException {
        requireRegularSessionFile(file);
        MessageDigest digest = sha256Digest();
        try (var input = Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void requireRegularSessionFile(Path file) throws IOException {
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("runtime session entry must be a regular non-symlink file: " + file.getFileName());
        }
    }

    private static String digest(byte[] bytes) {
        return HexFormat.of().formatHex(sha256Digest().digest(bytes));
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record SessionMetadata(Path file, String snapshotId, String sessionId, Instant importedAt) {
        private SessionMetadata {
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(snapshotId, "snapshotId");
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(importedAt, "importedAt");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }

    private static void rejectUnsafeProjectEntry(Path project) throws IOException {
        if (Files.isSymbolicLink(project)) {
            throw new IOException("runtime observation project directory must not be a symbolic link");
        }
    }

    private static void requireProjectDirectory(Path project) throws IOException {
        if (!Files.isDirectory(project, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("runtime observation project entry must be a directory");
        }
    }

    private static ReentrantLock[] locks() {
        ReentrantLock[] result = new ReentrantLock[LOCK_STRIPES];
        for (int index = 0; index < result.length; index++) result[index] = new ReentrantLock();
        return result;
    }

    private record LockedProject(BoundedFileLease lease) implements AutoCloseable {
        private LockedProject {
            Objects.requireNonNull(lease, "lease");
        }

        @Override
        public void close() throws IOException {
            lease.close();
        }
    }
}
