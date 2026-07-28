package com.minos.incremental;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Persistance locale, versionnée et vérifiée des snapshots d'empreintes M7.
 *
 * <p>La publication d'un snapshot et sa promotion active sont séparées. Un même
 * {@code projectId + indexSnapshotId} est immuable : une republication identique
 * est idempotente, une republication avec un autre contenu est refusée.</p>
 */
public final class FileProjectFingerprintSnapshotStore implements ProjectFingerprintSnapshotStore {

    private static final int SNAPSHOT_MAGIC = 0x4D4E4650; // MNFP
    private static final int POINTER_MAGIC = 0x4D4E4641; // MNFA
    private static final int FORMAT_VERSION = 1;
    private static final int MAX_FILES = 10_000_000;
    private static final int MAX_STRING_BYTES = 8 * 1024 * 1024;
    private static final String ACTIVE_FILE = "active.pointer";
    private static final BuildDescriptorPolicy BUILD_DESCRIPTOR_POLICY = BuildDescriptorPolicy.m24Defaults();
    private static final HexFormat HEX = HexFormat.of();

    private final Path storageRoot;

    public FileProjectFingerprintSnapshotStore(Path storageRoot) throws IOException {
        this.storageRoot = Objects.requireNonNull(storageRoot, "storageRoot")
                .toAbsolutePath()
                .normalize();
        Files.createDirectories(this.storageRoot);
    }

    @Override
    public ProjectFingerprintSnapshot publish(
            UUID projectId,
            String indexSnapshotId,
            ProjectFingerprint fingerprint
    ) throws IOException {
        Objects.requireNonNull(projectId, "projectId");
        indexSnapshotId = requireText(indexSnapshotId, "indexSnapshotId");
        Objects.requireNonNull(fingerprint, "fingerprint");
        verifyFingerprint(fingerprint);

        ProjectFingerprintSnapshot snapshot = new ProjectFingerprintSnapshot(projectId, indexSnapshotId, fingerprint);
        Path projectDirectory = projectDirectory(projectId);
        Files.createDirectories(projectDirectory);
        String idHash = sha256(indexSnapshotId);
        Path temporary = Files.createTempFile(projectDirectory, ".fingerprint-", ".tmp");
        try {
            String checksum = writeSnapshot(temporary, snapshot);
            String fileName = snapshotFileName(idHash, checksum);
            Path target = projectDirectory.resolve(fileName);
            Optional<Path> existing = findSnapshotFile(projectDirectory, idHash);
            if (existing.isPresent()) {
                if (!existing.orElseThrow().getFileName().toString().equals(fileName)) {
                    throw new IllegalStateException("fingerprint snapshot already exists with different content: " + indexSnapshotId);
                }
                Files.deleteIfExists(temporary);
                return readSnapshot(target, projectId, indexSnapshotId);
            }
            moveAtomically(temporary, target);
            return snapshot;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public void promote(UUID projectId, String indexSnapshotId) throws IOException {
        Objects.requireNonNull(projectId, "projectId");
        indexSnapshotId = requireText(indexSnapshotId, "indexSnapshotId");
        Path directory = projectDirectory(projectId);
        Optional<Path> snapshot = findSnapshotFile(directory, sha256(indexSnapshotId));
        if (snapshot.isEmpty()) {
            throw new IllegalArgumentException("fingerprint snapshot does not exist: " + indexSnapshotId);
        }
        readSnapshot(snapshot.orElseThrow(), projectId, indexSnapshotId);
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, ".active-", ".tmp");
        try {
            writePointer(temporary, indexSnapshotId);
            moveAtomically(temporary, directory.resolve(ACTIVE_FILE));
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public Optional<ProjectFingerprintSnapshot> loadActive(UUID projectId) throws IOException {
        Objects.requireNonNull(projectId, "projectId");
        Path pointer = projectDirectory(projectId).resolve(ACTIVE_FILE);
        if (!Files.isRegularFile(pointer)) {
            return Optional.empty();
        }
        String indexSnapshotId = readPointer(pointer);
        Optional<Path> snapshot = findSnapshotFile(projectDirectory(projectId), sha256(indexSnapshotId));
        if (snapshot.isEmpty()) {
            throw new IOException("active fingerprint snapshot is missing: " + indexSnapshotId);
        }
        return Optional.of(readSnapshot(snapshot.orElseThrow(), projectId, indexSnapshotId));
    }

    private static String writeSnapshot(Path path, ProjectFingerprintSnapshot snapshot) throws IOException {
        MessageDigest digest = digest();
        try (OutputStream raw = Files.newOutputStream(path);
             DigestOutputStream checked = new DigestOutputStream(new BufferedOutputStream(raw), digest);
             DataOutputStream output = new DataOutputStream(checked)) {
            output.writeInt(SNAPSHOT_MAGIC);
            output.writeInt(FORMAT_VERSION);
            writeString(output, snapshot.projectId().toString());
            writeString(output, snapshot.indexSnapshotId());
            writeFingerprint(output, snapshot.fingerprint());
        }
        return HEX.formatHex(digest.digest());
    }

    private static ProjectFingerprintSnapshot readSnapshot(
            Path path,
            UUID expectedProjectId,
            String expectedIndexSnapshotId
    ) throws IOException {
        String fileName = path.getFileName().toString();
        String expectedChecksum = checksumFromFileName(fileName);
        MessageDigest digest = digest();
        ProjectFingerprintSnapshot snapshot;
        try (InputStream raw = Files.newInputStream(path);
             DigestInputStream checked = new DigestInputStream(new BufferedInputStream(raw), digest);
             DataInputStream input = new DataInputStream(checked)) {
            if (input.readInt() != SNAPSHOT_MAGIC) throw new IOException("invalid fingerprint snapshot magic");
            if (input.readInt() != FORMAT_VERSION) throw new IOException("unsupported fingerprint snapshot format");
            UUID projectId = UUID.fromString(readString(input));
            String indexSnapshotId = readString(input);
            ProjectFingerprint fingerprint = readFingerprint(input);
            if (input.read() != -1) throw new IOException("unexpected trailing fingerprint snapshot data");
            snapshot = new ProjectFingerprintSnapshot(projectId, indexSnapshotId, fingerprint);
        } catch (EOFException exception) {
            throw new IOException("truncated fingerprint snapshot", exception);
        }
        String actualChecksum = HEX.formatHex(digest.digest());
        if (!actualChecksum.equals(expectedChecksum)) throw new IOException("fingerprint snapshot checksum mismatch");
        if (!snapshot.projectId().equals(expectedProjectId)) throw new IOException("fingerprint snapshot project mismatch");
        if (!snapshot.indexSnapshotId().equals(expectedIndexSnapshotId)) throw new IOException("fingerprint snapshot id mismatch");
        verifyFingerprint(snapshot.fingerprint());
        return snapshot;
    }

    private static void writeFingerprint(DataOutputStream output, ProjectFingerprint fingerprint) throws IOException {
        writeString(output, fingerprint.projectHash());
        writeString(output, fingerprint.buildHash());
        output.writeInt(fingerprint.files().size());
        for (FileFingerprint file : fingerprint.files()) {
            writeString(output, file.relativePath());
            output.writeLong(file.size());
            writeString(output, file.sha256());
        }
    }

    private static ProjectFingerprint readFingerprint(DataInputStream input) throws IOException {
        String projectHash = readString(input);
        String buildHash = readString(input);
        int count = input.readInt();
        if (count < 0 || count > MAX_FILES) throw new IOException("invalid fingerprint file count: " + count);
        List<FileFingerprint> files = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            files.add(new FileFingerprint(readString(input), input.readLong(), readString(input)));
        }
        return new ProjectFingerprint(projectHash, buildHash, files);
    }

    private static void verifyFingerprint(ProjectFingerprint fingerprint) throws IOException {
        Set<String> paths = new HashSet<>();
        String previous = null;
        for (FileFingerprint file : fingerprint.files()) {
            if (!paths.add(file.relativePath())) throw new IOException("duplicate fingerprint path: " + file.relativePath());
            if (previous != null && previous.compareTo(file.relativePath()) >= 0) {
                throw new IOException("fingerprint paths must be strictly sorted");
            }
            previous = file.relativePath();
        }
        String projectHash = aggregateHash(fingerprint.files());
        if (!projectHash.equals(fingerprint.projectHash())) throw new IOException("fingerprint project hash mismatch");
        String buildHash = aggregateHash(fingerprint.files().stream()
                .filter(file -> BUILD_DESCRIPTOR_POLICY.isBuildDescriptor(Path.of(file.relativePath())))
                .toList());
        if (!buildHash.equals(fingerprint.buildHash())) throw new IOException("fingerprint build hash mismatch");
    }

    private Path projectDirectory(UUID projectId) {
        return storageRoot.resolve(projectId.toString());
    }

    private static Optional<Path> findSnapshotFile(Path directory, String idHash) throws IOException {
        if (!Files.isDirectory(directory)) return Optional.empty();
        try (var stream = Files.list(directory)) {
            List<Path> matches = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith(idHash + "-")
                            && path.getFileName().toString().endsWith(".bin"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
            if (matches.size() > 1) throw new IOException("multiple immutable fingerprint snapshots found for id hash " + idHash);
            return matches.stream().findFirst();
        }
    }

    private static String snapshotFileName(String idHash, String checksum) {
        return idHash + "-" + checksum + ".bin";
    }

    private static String checksumFromFileName(String fileName) throws IOException {
        int separator = fileName.indexOf('-');
        if (separator < 0 || !fileName.endsWith(".bin")) throw new IOException("invalid fingerprint snapshot file name");
        return fileName.substring(separator + 1, fileName.length() - 4);
    }

    private static void writePointer(Path path, String indexSnapshotId) throws IOException {
        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))) {
            output.writeInt(POINTER_MAGIC);
            output.writeInt(FORMAT_VERSION);
            writeString(output, indexSnapshotId);
        }
    }

    private static String readPointer(Path path) throws IOException {
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            if (input.readInt() != POINTER_MAGIC) throw new IOException("invalid active fingerprint pointer magic");
            if (input.readInt() != FORMAT_VERSION) throw new IOException("unsupported active fingerprint pointer format");
            String indexSnapshotId = readString(input);
            if (input.read() != -1) throw new IOException("unexpected trailing active fingerprint pointer data");
            return indexSnapshotId;
        } catch (EOFException exception) {
            throw new IOException("truncated active fingerprint pointer", exception);
        }
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = requireText(value, "string").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) throw new IOException("string too large");
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_STRING_BYTES) throw new IOException("invalid string length: " + length);
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new EOFException("truncated string");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String aggregateHash(List<FileFingerprint> files) {
        MessageDigest digest = digest();
        for (FileFingerprint file : files) {
            update(digest, file.relativePath());
            update(digest, Long.toString(file.size()));
            update(digest, file.sha256());
        }
        return HEX.formatHex(digest.digest());
    }

    private static String sha256(String value) {
        MessageDigest digest = digest();
        update(digest, value);
        return HEX.formatHex(digest.digest());
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " must not be blank");
        return value;
    }
}
