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
import java.nio.file.attribute.FileTime;
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
import java.util.PriorityQueue;
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
    private static final BuildDescriptorPolicy CURRENT_BUILD_DESCRIPTOR_POLICY = BuildDescriptorPolicy.m24Defaults();
    private static final BuildDescriptorPolicy LEGACY_BUILD_DESCRIPTOR_POLICY = BuildDescriptorPolicy.m17Defaults();
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

        ProjectFingerprintSnapshot snapshot = new ProjectFingerprintSnapshot(
                projectId,
                indexSnapshotId,
                fingerprint
        );
        Path projectDirectory = projectDirectory(projectId);
        Files.createDirectories(projectDirectory);
        String idHash = sha256(indexSnapshotId);
        Path temporary = Files.createTempFile(projectDirectory, ".fingerprint-", ".tmp");
        try {
            String checksum = writeSnapshot(temporary, snapshot);
            String fileName = snapshotFileName(idHash, checksum);
            Path target = projectDirectory.resolve(fileName);
            List<Path> existing = filesForIdHash(projectDirectory, idHash);
            if (!existing.isEmpty()) {
                if (existing.size() == 1 && existing.getFirst().getFileName().toString().equals(fileName)) {
                    ProjectFingerprintSnapshot current = readVerifiedSnapshot(projectId, existing.getFirst());
                    if (!current.equals(snapshot)) {
                        throw new IOException("fingerprint snapshot checksum collision for index snapshot: "
                                + indexSnapshotId);
                    }
                    return current;
                }
                throw new IOException("fingerprint snapshot already exists with different content for index snapshot: "
                        + indexSnapshotId);
            }
            publishAtomically(temporary, target);
            return snapshot;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public void promote(UUID projectId, String indexSnapshotId) throws IOException {
        Objects.requireNonNull(projectId, "projectId");
        indexSnapshotId = requireText(indexSnapshotId, "indexSnapshotId");
        Path projectDirectory = projectDirectory(projectId);
        List<Path> matches = filesForIdHash(projectDirectory, sha256(indexSnapshotId));
        if (matches.isEmpty()) {
            throw new IOException("fingerprint snapshot is not published for index snapshot: " + indexSnapshotId);
        }
        if (matches.size() != 1) {
            throw new IOException("multiple fingerprint snapshots found for index snapshot: " + indexSnapshotId);
        }

        Path snapshotFile = matches.getFirst();
        ProjectFingerprintSnapshot snapshot = readVerifiedSnapshot(projectId, snapshotFile);
        if (!snapshot.indexSnapshotId().equals(indexSnapshotId)) {
            throw new IOException("fingerprint snapshot id hash collision");
        }
        String checksum = checksum(snapshotFile);
        Path temporaryPointer = Files.createTempFile(projectDirectory, ".active-", ".tmp");
        try {
            writePointer(temporaryPointer, new ActivePointer(
                    indexSnapshotId,
                    snapshotFile.getFileName().toString(),
                    checksum,
                    snapshot.fingerprint().projectSha256(),
                    snapshot.fingerprint().buildSha256(),
                    snapshot.fingerprint().fileCount()
            ));
            replaceAtomically(temporaryPointer, projectDirectory.resolve(ACTIVE_FILE));
        } finally {
            Files.deleteIfExists(temporaryPointer);
        }
    }

    @Override
    public Optional<ProjectFingerprintSnapshot> load(UUID projectId, String indexSnapshotId) throws IOException {
        Objects.requireNonNull(projectId, "projectId");
        indexSnapshotId = requireText(indexSnapshotId, "indexSnapshotId");
        Path projectDirectory = projectDirectory(projectId);
        List<Path> matches = filesForIdHash(projectDirectory, sha256(indexSnapshotId));
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        if (matches.size() != 1) {
            throw new IOException("multiple fingerprint snapshots found for index snapshot: " + indexSnapshotId);
        }
        ProjectFingerprintSnapshot snapshot = readVerifiedSnapshot(projectId, matches.getFirst());
        if (!snapshot.indexSnapshotId().equals(indexSnapshotId)) {
            throw new IOException("fingerprint snapshot id hash collision");
        }
        return Optional.of(snapshot);
    }

    @Override
    public Optional<ProjectFingerprintSnapshot> loadActive(UUID projectId) throws IOException {
        Objects.requireNonNull(projectId, "projectId");
        Path projectDirectory = projectDirectory(projectId);
        Path pointerFile = projectDirectory.resolve(ACTIVE_FILE);
        if (!Files.isRegularFile(pointerFile)) {
            return Optional.empty();
        }

        ActivePointer pointer = readPointer(pointerFile);
        Path snapshotFile = resolveFile(projectDirectory, pointer.fileName());
        if (!Files.isRegularFile(snapshotFile)) {
            throw new IOException("active fingerprint snapshot file is missing: " + snapshotFile);
        }
        String actualChecksum = checksum(snapshotFile);
        if (!actualChecksum.equals(pointer.sha256())) {
            throw new IOException("active fingerprint snapshot checksum mismatch");
        }
        ProjectFingerprintSnapshot snapshot = readVerifiedSnapshot(projectId, snapshotFile);
        if (!snapshot.indexSnapshotId().equals(pointer.indexSnapshotId())) {
            throw new IOException("active fingerprint snapshot id does not match its pointer");
        }
        ProjectFingerprint fingerprint = snapshot.fingerprint();
        if (!fingerprint.projectSha256().equals(pointer.projectSha256())
                || !fingerprint.buildSha256().equals(pointer.buildSha256())
                || fingerprint.fileCount() != pointer.fileCount()) {
            throw new IOException("active fingerprint snapshot metadata does not match its pointer");
        }
        return Optional.of(snapshot);
    }

    @Override
    public List<String> listIndexSnapshotIds(UUID projectId) throws IOException {
        Objects.requireNonNull(projectId, "projectId");
        Path projectDirectory = projectDirectory(projectId);
        if (!Files.isDirectory(projectDirectory)) {
            return List.of();
        }

        List<String> ids = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        try (var stream = Files.list(projectDirectory)) {
            for (Path file : stream
                    .filter(Files::isRegularFile)
                    .filter(path -> isSnapshotFile(path.getFileName().toString()))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList()) {
                ProjectFingerprintSnapshot snapshot = readVerifiedSnapshot(projectId, file);
                if (!unique.add(snapshot.indexSnapshotId())) {
                    throw new IOException("duplicate fingerprint snapshot id in history: "
                            + snapshot.indexSnapshotId());
                }
                ids.add(snapshot.indexSnapshotId());
            }
        }
        ids.sort(String::compareTo);
        return List.copyOf(ids);
    }

    public Path storageRoot() {
        return storageRoot;
    }

    /** Applies bounded historical retention while protecting active and caller-referenced ids. */
    public synchronized FingerprintRetentionResult compact(
            UUID projectId,
            Set<String> additionallyProtectedSnapshotIds,
            int maxHistoricalSnapshots
    ) throws IOException {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(additionallyProtectedSnapshotIds, "additionallyProtectedSnapshotIds");
        if (maxHistoricalSnapshots < 0) {
            throw new IllegalArgumentException("maxHistoricalSnapshots must not be negative");
        }
        Path projectDirectory = projectDirectory(projectId);
        if (!Files.isDirectory(projectDirectory)) return new FingerprintRetentionResult(0, 0);

        String activeFileName = null;
        Path activePointer = projectDirectory.resolve(ACTIVE_FILE);
        if (Files.isRegularFile(activePointer, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            ActivePointer pointer = readPointer(activePointer);
            Path activeFile = resolveFile(projectDirectory, pointer.fileName());
            if (!Files.isRegularFile(activeFile, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("active fingerprint snapshot file is missing: " + activeFile);
            }
            activeFileName = pointer.fileName();
        }

        Set<String> protectedPrefixes = new HashSet<>();
        for (String snapshotId : additionallyProtectedSnapshotIds) {
            protectedPrefixes.add("fingerprint-" + sha256(requireText(snapshotId, "protectedSnapshotId")) + "-");
        }
        Comparator<FingerprintFile> oldestFirst = Comparator
                .comparing(FingerprintFile::lastModified)
                .thenComparing(FingerprintFile::fileName);
        PriorityQueue<FingerprintFile> historical = new PriorityQueue<>(oldestFirst);
        int protectedCount = 0;
        int deleted = 0;
        try (var stream = Files.newDirectoryStream(projectDirectory)) {
            for (Path file : stream) {
                String fileName = file.getFileName().toString();
                if (!isSnapshotFile(fileName)
                        || !Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                boolean protectedFile = fileName.equals(activeFileName)
                        || protectedPrefixes.stream().anyMatch(fileName::startsWith);
                if (protectedFile) {
                    protectedCount++;
                    continue;
                }
                FingerprintFile candidate = new FingerprintFile(
                        file, fileName,
                        Files.getLastModifiedTime(file, java.nio.file.LinkOption.NOFOLLOW_LINKS));
                if (historical.size() < maxHistoricalSnapshots) {
                    historical.add(candidate);
                } else if (maxHistoricalSnapshots > 0
                        && oldestFirst.compare(candidate, historical.element()) > 0) {
                    Files.deleteIfExists(historical.remove().path());
                    deleted++;
                    historical.add(candidate);
                } else {
                    Files.deleteIfExists(candidate.path());
                    deleted++;
                }
            }
        }
        return new FingerprintRetentionResult(protectedCount + historical.size(), deleted);
    }

    private Path projectDirectory(UUID projectId) {
        return storageRoot.resolve(projectId.toString());
    }

    private static String writeSnapshot(Path file, ProjectFingerprintSnapshot snapshot) throws IOException {
        MessageDigest digest = sha256Digest();
        try (OutputStream fileOutput = Files.newOutputStream(file);
             DigestOutputStream digestOutput = new DigestOutputStream(fileOutput, digest);
             DataOutputStream output = new DataOutputStream(new BufferedOutputStream(digestOutput))) {
            output.writeInt(SNAPSHOT_MAGIC);
            output.writeInt(FORMAT_VERSION);
            output.writeLong(snapshot.projectId().getMostSignificantBits());
            output.writeLong(snapshot.projectId().getLeastSignificantBits());
            writeString(output, snapshot.indexSnapshotId());
            writeString(output, snapshot.fingerprint().projectSha256());
            writeString(output, snapshot.fingerprint().buildSha256());
            output.writeInt(snapshot.fingerprint().files().size());
            for (FileFingerprint fingerprint : snapshot.fingerprint().files()) {
                writeString(output, fingerprint.relativePath());
                output.writeLong(fingerprint.sizeBytes());
                writeString(output, fingerprint.sha256());
            }
        }
        return HEX.formatHex(digest.digest());
    }

    private static ProjectFingerprintSnapshot readVerifiedSnapshot(UUID expectedProjectId, Path file)
            throws IOException {
        verifyFileNameChecksum(file);
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            requireHeader(input, SNAPSHOT_MAGIC, "fingerprint snapshot");
            UUID projectId = new UUID(input.readLong(), input.readLong());
            String indexSnapshotId = readString(input, "indexSnapshotId");
            String projectSha256 = readString(input, "projectSha256");
            String buildSha256 = readString(input, "buildSha256");
            int fileCount = readCount(input, MAX_FILES, "fileCount");
            List<FileFingerprint> files = new ArrayList<>(fileCount);
            for (int index = 0; index < fileCount; index++) {
                String relativePath = readString(input, "relativePath");
                long sizeBytes = input.readLong();
                if (sizeBytes < 0) {
                    throw new IOException("negative fingerprint file size");
                }
                String sha256 = readString(input, "fileSha256");
                try {
                    files.add(new FileFingerprint(relativePath, sizeBytes, sha256));
                } catch (IllegalArgumentException exception) {
                    throw new IOException("invalid fingerprint entry", exception);
                }
            }
            if (input.read() != -1) {
                throw new IOException("unexpected trailing data in fingerprint snapshot");
            }
            if (!projectId.equals(expectedProjectId)) {
                throw new IOException("fingerprint snapshot belongs to another project");
            }
            ProjectFingerprint fingerprint;
            try {
                fingerprint = new ProjectFingerprint(projectSha256, buildSha256, files);
            } catch (IllegalArgumentException exception) {
                throw new IOException("invalid fingerprint snapshot payload", exception);
            }
            verifyFingerprint(fingerprint);
            return new ProjectFingerprintSnapshot(projectId, indexSnapshotId, fingerprint);
        } catch (EOFException exception) {
            throw new IOException("truncated fingerprint snapshot", exception);
        }
    }

    private static void verifyFingerprint(ProjectFingerprint fingerprint) throws IOException {
        String expectedProject = aggregateHash(fingerprint.files());
        if (!expectedProject.equals(fingerprint.projectSha256())) {
            throw new IOException("project fingerprint aggregate mismatch");
        }
        String currentBuild = buildHash(fingerprint.files(), CURRENT_BUILD_DESCRIPTOR_POLICY);
        if (currentBuild.equals(fingerprint.buildSha256())) {
            return;
        }
        // FORMAT_VERSION=1 snapshots created before M24 used the M17 descriptor set.
        // Accept that exact legacy hash so additive build markers do not invalidate
        // an otherwise immutable historical snapshot.
        String legacyBuild = buildHash(fingerprint.files(), LEGACY_BUILD_DESCRIPTOR_POLICY);
        if (!legacyBuild.equals(fingerprint.buildSha256())) {
            throw new IOException("build fingerprint aggregate mismatch");
        }
    }

    private static String aggregateHash(List<FileFingerprint> files) {
        MessageDigest digest = sha256Digest();
        for (FileFingerprint file : files) {
            update(digest, file.relativePath());
            digest.update((byte) 0);
            update(digest, Long.toString(file.sizeBytes()));
            digest.update((byte) 0);
            update(digest, file.sha256());
            digest.update((byte) '\n');
        }
        return HEX.formatHex(digest.digest());
    }

    private static String buildHash(List<FileFingerprint> files, BuildDescriptorPolicy policy) {
        return aggregateHash(files.stream()
                .filter(file -> policy.isBuildDescriptor(Path.of(file.relativePath())))
                .toList());
    }

    private static void writePointer(Path file, ActivePointer pointer) throws IOException {
        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))) {
            output.writeInt(POINTER_MAGIC);
            output.writeInt(FORMAT_VERSION);
            writeString(output, pointer.indexSnapshotId());
            writeString(output, pointer.fileName());
            writeString(output, pointer.sha256());
            writeString(output, pointer.projectSha256());
            writeString(output, pointer.buildSha256());
            output.writeInt(pointer.fileCount());
        }
    }

    private static ActivePointer readPointer(Path file) throws IOException {
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            requireHeader(input, POINTER_MAGIC, "fingerprint active pointer");
            ActivePointer pointer;
            try {
                pointer = new ActivePointer(
                        readString(input, "indexSnapshotId"),
                        readString(input, "fileName"),
                        readString(input, "sha256"),
                        readString(input, "projectSha256"),
                        readString(input, "buildSha256"),
                        readCount(input, MAX_FILES, "fileCount")
                );
            } catch (IllegalArgumentException exception) {
                throw new IOException("invalid fingerprint active pointer", exception);
            }
            if (input.read() != -1) {
                throw new IOException("unexpected trailing data in fingerprint active pointer");
            }
            return pointer;
        } catch (EOFException exception) {
            throw new IOException("truncated fingerprint active pointer", exception);
        }
    }

    private static List<Path> filesForIdHash(Path projectDirectory, String idHash) throws IOException {
        if (!Files.isDirectory(projectDirectory)) {
            return List.of();
        }
        String prefix = "fingerprint-" + idHash + "-";
        try (var stream = Files.list(projectDirectory)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith(prefix))
                    .filter(path -> path.getFileName().toString().endsWith(".bin"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
    }

    private static void verifyFileNameChecksum(Path file) throws IOException {
        String name = file.getFileName().toString();
        if (!isSnapshotFile(name)) {
            throw new IOException("invalid fingerprint snapshot file name: " + name);
        }
        int checksumStart = name.lastIndexOf('-') + 1;
        String expected = name.substring(checksumStart, name.length() - ".bin".length());
        String actual = checksum(file);
        if (!expected.equals(actual)) {
            throw new IOException("fingerprint snapshot checksum mismatch");
        }
    }

    private static boolean isSnapshotFile(String name) {
        if (!name.startsWith("fingerprint-") || !name.endsWith(".bin")) {
            return false;
        }
        String body = name.substring("fingerprint-".length(), name.length() - ".bin".length());
        int separator = body.indexOf('-');
        return separator == 64
                && body.length() == 64 + 1 + 64
                && isHex(body.substring(0, 64))
                && isHex(body.substring(65));
    }

    private static boolean isHex(String value) {
        if (value.length() != 64) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            boolean hex = (current >= '0' && current <= '9')
                    || (current >= 'a' && current <= 'f');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    private static String snapshotFileName(String idHash, String checksum) {
        return "fingerprint-" + idHash + "-" + checksum + ".bin";
    }

    private static Path resolveFile(Path projectDirectory, String fileName) throws IOException {
        if (fileName.contains("/") || fileName.contains("\\") || !isSnapshotFile(fileName)) {
            throw new IOException("invalid fingerprint snapshot file name in pointer");
        }
        Path resolved = projectDirectory.resolve(fileName).normalize();
        if (!resolved.getParent().equals(projectDirectory)) {
            throw new IOException("fingerprint snapshot pointer escapes project directory");
        }
        return resolved;
    }

    private static void requireHeader(DataInputStream input, int expectedMagic, String label) throws IOException {
        int magic = input.readInt();
        int version = input.readInt();
        if (magic != expectedMagic) {
            throw new IOException("invalid " + label + " magic");
        }
        if (version != FORMAT_VERSION) {
            throw new IOException("unsupported " + label + " format version: " + version);
        }
    }

    private static int readCount(DataInputStream input, int max, String label) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > max) {
            throw new IOException("invalid " + label + ": " + count);
        }
        return count;
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = requireText(value, "string").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) {
            throw new IOException("string is too large");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input, String label) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_STRING_BYTES) {
            throw new IOException("invalid " + label + " byte length: " + length);
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException("truncated " + label);
        }
        String value = new String(bytes, StandardCharsets.UTF_8);
        if (value.isBlank()) {
            throw new IOException(label + " must not be blank");
        }
        return value;
    }

    private static String checksum(Path file) throws IOException {
        MessageDigest digest = sha256Digest();
        try (InputStream input = new DigestInputStream(Files.newInputStream(file), digest)) {
            input.transferTo(OutputStream.nullOutputStream());
        }
        return HEX.formatHex(digest.digest());
    }

    private static String sha256(String value) {
        MessageDigest digest = sha256Digest();
        update(digest, value);
        return HEX.formatHex(digest.digest());
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }

    private static void publishAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private static void replaceAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private record ActivePointer(
            String indexSnapshotId,
            String fileName,
            String sha256,
            String projectSha256,
            String buildSha256,
            int fileCount
    ) {
        private ActivePointer {
            indexSnapshotId = requireText(indexSnapshotId, "indexSnapshotId");
            fileName = requireText(fileName, "fileName");
            sha256 = FileFingerprint.requireSha256(sha256);
            projectSha256 = FileFingerprint.requireSha256(projectSha256);
            buildSha256 = FileFingerprint.requireSha256(buildSha256);
            if (fileCount < 0) {
                throw new IllegalArgumentException("fileCount must be >= 0");
            }
        }
    }

    private record FingerprintFile(Path path, String fileName, FileTime lastModified) { }

    public record FingerprintRetentionResult(int retainedSnapshots, int deletedSnapshots) {
        public FingerprintRetentionResult {
            if (retainedSnapshots < 0 || deletedSnapshots < 0) {
                throw new IllegalArgumentException("fingerprint retention counts must not be negative");
            }
        }
    }
}
