package com.minos.incremental;

import com.minos.io.BoundedInputStream;
import com.minos.io.CommitUncertainException;
import com.minos.io.DurableAtomicFile;
import com.minos.source.SourceBudgetPolicy;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
    static final int MAX_FILES = Math.toIntExact(SourceBudgetPolicy.DEFAULT_MAX_FILES);
    static final int MAX_STRING_BYTES = 64 * 1024;
    static final long MAX_SNAPSHOT_BYTES = 64L * 1024L * 1024L;
    private static final long MAX_POINTER_BYTES = 512L * 1024L;
    private static final int MAX_INITIAL_LIST_CAPACITY = 16_384;
    private static final String ACTIVE_FILE = "active.pointer";
    private static final BuildDescriptorPolicy CURRENT_BUILD_DESCRIPTOR_POLICY = BuildDescriptorPolicy.m24Defaults();
    private static final BuildDescriptorPolicy LEGACY_BUILD_DESCRIPTOR_POLICY = BuildDescriptorPolicy.m17Defaults();
    private static final HexFormat HEX = HexFormat.of();
    private static final String FIELD_PROJECT_SHA256 = "projectSha256";
    private static final String FIELD_BUILD_SHA256 = "buildSha256";
    private static final String FINGERPRINT_ACTIVE_POINTER_LABEL = "fingerprint active pointer";

    private final Path storageRoot;

    public FileProjectFingerprintSnapshotStore(Path storageRoot) throws IOException {
        this.storageRoot = Objects.requireNonNull(storageRoot, "storageRoot")
                .toAbsolutePath()
                .normalize();
        DurableAtomicFile.ensureDirectory(this.storageRoot, "fingerprint storage root");
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
        DurableAtomicFile.ensureDirectory(projectDirectory, "fingerprint project directory");
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
            try {
                DurableAtomicFile.publish(temporary, target, "fingerprint snapshot publication");
            } catch (CommitUncertainException uncertain) {
                if (regularFileExists(target, "published fingerprint snapshot")) {
                    ProjectFingerprintSnapshot visible = readVerifiedSnapshot(projectId, target);
                    if (visible.equals(snapshot)) return visible;
                }
                throw uncertain;
            }
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
            try {
                DurableAtomicFile.replace(
                        temporaryPointer,
                        projectDirectory.resolve(ACTIVE_FILE),
                        "active fingerprint pointer replacement");
            } catch (CommitUncertainException uncertain) {
                Optional<ProjectFingerprintSnapshot> visible = loadActive(projectId);
                if (visible.map(ProjectFingerprintSnapshot::indexSnapshotId).filter(indexSnapshotId::equals).isPresent()) {
                    return;
                }
                throw uncertain;
            }
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
        Path projectDirectory = existingProjectDirectory(projectId);
        if (projectDirectory == null) return Optional.empty();
        Path pointerFile = projectDirectory.resolve(ACTIVE_FILE);
        if (!regularFileExists(pointerFile, "active fingerprint pointer")) return Optional.empty();

        ActivePointer pointer = readPointer(pointerFile);
        Path snapshotFile = resolveFile(projectDirectory, pointer.fileName());
        if (!regularFileExists(snapshotFile, "active fingerprint snapshot")) {
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
        Path projectDirectory = existingProjectDirectory(projectId);
        if (projectDirectory == null) return List.of();

        List<String> ids = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (Path file : snapshotFiles(projectDirectory)) {
            ProjectFingerprintSnapshot snapshot = readVerifiedSnapshot(projectId, file);
            if (!unique.add(snapshot.indexSnapshotId())) {
                throw new IOException("duplicate fingerprint snapshot id in history: "
                        + snapshot.indexSnapshotId());
            }
            ids.add(snapshot.indexSnapshotId());
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
        Path projectDirectory = existingProjectDirectory(projectId);
        if (projectDirectory == null) return new FingerprintRetentionResult(0, 0);

        String activeFileName = null;
        Path activePointer = projectDirectory.resolve(ACTIVE_FILE);
        if (regularFileExists(activePointer, "active fingerprint pointer")) {
            ActivePointer pointer = readPointer(activePointer);
            Path activeFile = resolveFile(projectDirectory, pointer.fileName());
            if (!regularFileExists(activeFile, "active fingerprint snapshot")) {
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
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(projectDirectory)) {
            for (Path file : stream) {
                String fileName = file.getFileName().toString();
                if (!isSnapshotFile(fileName)) continue;
                requireRegularFile(file, "fingerprint snapshot");
                boolean protectedFile = fileName.equals(activeFileName)
                        || protectedPrefixes.stream().anyMatch(fileName::startsWith);
                if (protectedFile) {
                    protectedCount++;
                    continue;
                }
                FingerprintFile candidate = new FingerprintFile(
                        file, fileName,
                        Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS));
                if (historical.size() < maxHistoricalSnapshots) {
                    historical.add(candidate);
                } else if (maxHistoricalSnapshots > 0
                        && oldestFirst.compare(candidate, historical.element()) > 0) {
                    DurableAtomicFile.deleteIfExists(
                            historical.remove().path(), "fingerprint retention deletion");
                    deleted++;
                    historical.add(candidate);
                } else {
                    DurableAtomicFile.deleteIfExists(candidate.path(), "fingerprint retention deletion");
                    deleted++;
                }
            }
        }
        return new FingerprintRetentionResult(protectedCount + historical.size(), deleted);
    }

    private Path projectDirectory(UUID projectId) {
        return storageRoot.resolve(projectId.toString());
    }

    private Path existingProjectDirectory(UUID projectId) throws IOException {
        Path directory = projectDirectory(projectId);
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return null;
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("fingerprint project entry must be a non-symlink directory: " + directory);
        }
        return directory;
    }

    private static String writeSnapshot(Path file, ProjectFingerprintSnapshot snapshot) throws IOException {
        validateSnapshotEncoding(snapshot);
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
        requireBoundedRegularFile(file, "fingerprint snapshot", MAX_SNAPSHOT_BYTES);
        verifyFileNameChecksum(file);
        try (InputStream fileInput = Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
             BoundedInputStream boundedInput = new BoundedInputStream(
                     fileInput, MAX_SNAPSHOT_BYTES, "fingerprint snapshot");
             DataInputStream input = new DataInputStream(new BufferedInputStream(boundedInput))) {
            requireHeader(input, SNAPSHOT_MAGIC, "fingerprint snapshot");
            UUID projectId = new UUID(input.readLong(), input.readLong());
            String indexSnapshotId = readString(input, "indexSnapshotId");
            String projectSha256 = readString(input, FIELD_PROJECT_SHA256);
            String buildSha256 = readString(input, FIELD_BUILD_SHA256);
            int fileCount = readCount(input, MAX_FILES, "fileCount");
            List<FileFingerprint> files = new ArrayList<>(Math.min(fileCount, MAX_INITIAL_LIST_CAPACITY));
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

    private static void validateSnapshotEncoding(ProjectFingerprintSnapshot snapshot) throws IOException {
        int fileCount = snapshot.fingerprint().files().size();
        if (fileCount > MAX_FILES) {
            throw new IOException("fingerprint snapshot exceeds file count limit: " + fileCount + "/" + MAX_FILES);
        }
        long bytes = 2L * Integer.BYTES + 2L * Long.BYTES;
        bytes = addEncodedString(bytes, snapshot.indexSnapshotId(), "indexSnapshotId");
        bytes = addEncodedString(bytes, snapshot.fingerprint().projectSha256(), FIELD_PROJECT_SHA256);
        bytes = addEncodedString(bytes, snapshot.fingerprint().buildSha256(), FIELD_BUILD_SHA256);
        bytes = addSnapshotBytes(bytes, Integer.BYTES);
        for (FileFingerprint fingerprint : snapshot.fingerprint().files()) {
            bytes = addEncodedString(bytes, fingerprint.relativePath(), "relativePath");
            bytes = addSnapshotBytes(bytes, Long.BYTES);
            bytes = addEncodedString(bytes, fingerprint.sha256(), "fileSha256");
        }
    }

    private static long addEncodedString(long current, String value, String label) throws IOException {
        byte[] bytes = requireText(value, label).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) {
            throw new IOException(label + " exceeds UTF-8 byte limit: " + bytes.length + "/" + MAX_STRING_BYTES);
        }
        return addSnapshotBytes(addSnapshotBytes(current, Integer.BYTES), bytes.length);
    }

    private static long addSnapshotBytes(long current, long additional) throws IOException {
        long total;
        try {
            total = Math.addExact(current, additional);
        } catch (ArithmeticException exception) {
            throw new IOException("fingerprint snapshot size overflow", exception);
        }
        if (total > MAX_SNAPSHOT_BYTES) {
            throw new IOException("fingerprint snapshot exceeds byte limit: " + total + "/" + MAX_SNAPSHOT_BYTES);
        }
        return total;
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
        requireBoundedRegularFile(file, FINGERPRINT_ACTIVE_POINTER_LABEL, MAX_POINTER_BYTES);
        try (InputStream fileInput = Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
             BoundedInputStream boundedInput = new BoundedInputStream(
                     fileInput, MAX_POINTER_BYTES, FINGERPRINT_ACTIVE_POINTER_LABEL);
             DataInputStream input = new DataInputStream(new BufferedInputStream(boundedInput))) {
            requireHeader(input, POINTER_MAGIC, FINGERPRINT_ACTIVE_POINTER_LABEL);
            ActivePointer pointer;
            try {
                pointer = new ActivePointer(
                        readString(input, "indexSnapshotId"),
                        readString(input, "fileName"),
                        readString(input, "sha256"),
                        readString(input, FIELD_PROJECT_SHA256),
                        readString(input, FIELD_BUILD_SHA256),
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
        if (!Files.exists(projectDirectory, LinkOption.NOFOLLOW_LINKS)) return List.of();
        requireProjectDirectory(projectDirectory);
        String prefix = "fingerprint-" + idHash + "-";
        List<Path> matches = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(projectDirectory)) {
            for (Path path : stream) {
                String name = path.getFileName().toString();
                if (!name.startsWith(prefix) || !name.endsWith(".bin")) continue;
                requireRegularFile(path, "fingerprint snapshot");
                matches.add(path);
            }
        }
        matches.sort(Comparator.comparing(path -> path.getFileName().toString()));
        return List.copyOf(matches);
    }

    private static List<Path> snapshotFiles(Path projectDirectory) throws IOException {
        requireProjectDirectory(projectDirectory);
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(projectDirectory)) {
            for (Path path : stream) {
                if (!isSnapshotFile(path.getFileName().toString())) continue;
                requireRegularFile(path, "fingerprint snapshot");
                files.add(path);
            }
        }
        files.sort(Comparator.comparing(path -> path.getFileName().toString()));
        return List.copyOf(files);
    }

    private static void requireProjectDirectory(Path directory) throws IOException {
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("fingerprint project entry must be a non-symlink directory: " + directory);
        }
    }

    private static boolean regularFileExists(Path file, String label) throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return false;
        requireRegularFile(file, label);
        return true;
    }

    private static void requireRegularFile(Path file, String label) throws IOException {
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(label + " must be a regular non-symlink file: " + file);
        }
    }

    private static void requireBoundedRegularFile(Path file, String label, long maximumBytes) throws IOException {
        requireRegularFile(file, label);
        long size = Files.size(file);
        if (size > maximumBytes) {
            throw new IOException(label + " exceeds byte limit: " + size + "/" + maximumBytes);
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
            throw new IOException("string exceeds UTF-8 byte limit: " + bytes.length + "/" + MAX_STRING_BYTES);
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
        requireBoundedRegularFile(file, "fingerprint snapshot", MAX_SNAPSHOT_BYTES);
        MessageDigest digest = sha256Digest();
        try (InputStream fileInput = Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
             BoundedInputStream boundedInput = new BoundedInputStream(
                     fileInput, MAX_SNAPSHOT_BYTES, "fingerprint snapshot checksum");
             InputStream input = new DigestInputStream(boundedInput, digest)) {
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
            if (fileCount < 0 || fileCount > MAX_FILES) {
                throw new IllegalArgumentException("fileCount must be between 0 and " + MAX_FILES);
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
