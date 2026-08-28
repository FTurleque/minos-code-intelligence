package com.minos.incremental;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileProjectFingerprintSnapshotStoreTest {

    private static final int SNAPSHOT_MAGIC = 0x4D4E4650;
    private static final int FORMAT_VERSION = 1;
    private static final String ZERO_SHA256 = "0".repeat(64);

    private final ProjectFingerprintService fingerprintService = new ProjectFingerprintService();

    @Test
    void publishesHistorySeparatelyFromActivePromotionAndCanSwitchActiveSnapshot(@TempDir Path root)
            throws Exception {
        Path project = root.resolve("project");
        Path storage = root.resolve("storage");
        createProject(project, "class App { int value = 1; }");
        UUID projectId = UUID.randomUUID();
        FileProjectFingerprintSnapshotStore store = new FileProjectFingerprintSnapshotStore(storage);

        ProjectFingerprint first = fingerprintService.capture(project);
        store.publish(projectId, "index-1", first);
        assertTrue(store.load(projectId, "index-1").isPresent());
        assertTrue(store.loadActive(projectId).isEmpty());

        store.promote(projectId, "index-1");
        assertEquals("index-1", store.loadActive(projectId).orElseThrow().indexSnapshotId());

        Files.writeString(project.resolve("src/App.java"), "class App { int value = 2; }");
        ProjectFingerprint second = fingerprintService.capture(project);
        store.publish(projectId, "index-2", second);
        store.promote(projectId, "index-2");

        FileProjectFingerprintSnapshotStore reopened = new FileProjectFingerprintSnapshotStore(storage);
        assertEquals("index-2", reopened.loadActive(projectId).orElseThrow().indexSnapshotId());
        assertEquals(first, reopened.load(projectId, "index-1").orElseThrow().fingerprint());
        assertEquals(second, reopened.load(projectId, "index-2").orElseThrow().fingerprint());
        assertEquals(List.of("index-1", "index-2"), reopened.listIndexSnapshotIds(projectId));
    }

    @Test
    void identicalRepublicationIsIdempotentButHistoricalAssociationCannotBeRewritten(@TempDir Path root)
            throws Exception {
        Path project = root.resolve("project");
        createProject(project, "class App {}");
        FileProjectFingerprintSnapshotStore store = new FileProjectFingerprintSnapshotStore(root.resolve("storage"));
        UUID projectId = UUID.randomUUID();
        ProjectFingerprint first = fingerprintService.capture(project);

        ProjectFingerprintSnapshot published = store.publish(projectId, "index-1", first);
        assertEquals(published, store.publish(projectId, "index-1", first));

        Files.writeString(project.resolve("src/App.java"), "class App { void changed() {} }");
        ProjectFingerprint changed = fingerprintService.capture(project);
        IOException error = assertThrows(
                IOException.class,
                () -> store.publish(projectId, "index-1", changed)
        );
        assertTrue(error.getMessage().contains("different content"));
        assertEquals(first, store.load(projectId, "index-1").orElseThrow().fingerprint());
    }

    @Test
    void refusesPromotionBeforePublication(@TempDir Path root) throws Exception {
        FileProjectFingerprintSnapshotStore store = new FileProjectFingerprintSnapshotStore(root.resolve("storage"));
        IOException error = assertThrows(
                IOException.class,
                () -> store.promote(UUID.randomUUID(), "missing-index")
        );
        assertTrue(error.getMessage().contains("not published"));
    }

    @Test
    void detectsCorruptedHistoricalSnapshotBeforeReturningIt(@TempDir Path root) throws Exception {
        Path project = root.resolve("project");
        Path storage = root.resolve("storage");
        createProject(project, "class App {}");
        UUID projectId = UUID.randomUUID();
        FileProjectFingerprintSnapshotStore store = new FileProjectFingerprintSnapshotStore(storage);
        store.publish(projectId, "index-1", fingerprintService.capture(project));

        Path projectStorage = storage.resolve(projectId.toString());
        Path snapshot;
        try (var files = Files.list(projectStorage)) {
            snapshot = files
                    .filter(path -> path.getFileName().toString().endsWith(".bin"))
                    .findFirst()
                    .orElseThrow();
        }
        byte[] bytes = Files.readAllBytes(snapshot);
        bytes[bytes.length - 1] ^= 0x01;
        Files.write(snapshot, bytes);

        IOException error = assertThrows(IOException.class, () -> store.load(projectId, "index-1"));
        assertTrue(error.getMessage().contains("checksum mismatch"));
    }

    @Test
    void rejectsFingerprintModelAboveSourceFileBudgetBeforeCopyingEntries() {
        FileFingerprint repeated = new FileFingerprint("same.java", 0L, ZERO_SHA256);
        List<FileFingerprint> tooMany = Collections.nCopies(
                FileProjectFingerprintSnapshotStore.MAX_FILES + 1, repeated);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                new ProjectFingerprint(ZERO_SHA256, ZERO_SHA256, tooMany));

        assertTrue(error.getMessage().contains("exceeds source budget"));
    }

    @Test
    void rejectsOversizedSnapshotStringBeforePublishing(@TempDir Path root) throws Exception {
        Path project = root.resolve("project");
        createProject(project, "class App {}");
        FileProjectFingerprintSnapshotStore store = new FileProjectFingerprintSnapshotStore(root.resolve("storage"));
        ProjectFingerprint fingerprint = fingerprintService.capture(project);
        String oversizedId = "x".repeat(FileProjectFingerprintSnapshotStore.MAX_STRING_BYTES + 1);

        IOException error = assertThrows(IOException.class, () ->
                store.publish(UUID.randomUUID(), oversizedId, fingerprint));

        assertTrue(error.getMessage().contains("indexSnapshotId exceeds UTF-8 byte limit"));
    }

    @Test
    void rejectsSnapshotLargerThanGlobalByteLimitBeforeChecksumming(@TempDir Path root) throws Exception {
        Path storage = root.resolve("storage");
        UUID projectId = UUID.randomUUID();
        FileProjectFingerprintSnapshotStore store = new FileProjectFingerprintSnapshotStore(storage);
        Path projectStorage = Files.createDirectories(storage.resolve(projectId.toString()));
        String idHash = sha256("index-1".getBytes(StandardCharsets.UTF_8));
        Path oversized = projectStorage.resolve("fingerprint-" + idHash + "-" + ZERO_SHA256 + ".bin");
        try (FileChannel channel = FileChannel.open(
                oversized, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            channel.position(FileProjectFingerprintSnapshotStore.MAX_SNAPSHOT_BYTES);
            channel.write(ByteBuffer.wrap(new byte[]{1}));
        }

        IOException error = assertThrows(IOException.class, () -> store.load(projectId, "index-1"));

        assertTrue(error.getMessage().contains("fingerprint snapshot exceeds byte limit"));
    }

    @Test
    void rejectsDeclaredFileCountAboveLimitWithoutPreallocatingDeclaredCapacity(@TempDir Path root)
            throws Exception {
        Path storage = root.resolve("storage");
        UUID projectId = UUID.randomUUID();
        FileProjectFingerprintSnapshotStore store = new FileProjectFingerprintSnapshotStore(storage);
        Path projectStorage = Files.createDirectories(storage.resolve(projectId.toString()));
        Path raw = projectStorage.resolve("candidate.tmp");
        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(raw)))) {
            output.writeInt(SNAPSHOT_MAGIC);
            output.writeInt(FORMAT_VERSION);
            output.writeLong(projectId.getMostSignificantBits());
            output.writeLong(projectId.getLeastSignificantBits());
            writeString(output, "index-1");
            writeString(output, ZERO_SHA256);
            writeString(output, ZERO_SHA256);
            output.writeInt(FileProjectFingerprintSnapshotStore.MAX_FILES + 1);
        }
        String idHash = sha256("index-1".getBytes(StandardCharsets.UTF_8));
        String checksum = sha256(Files.readAllBytes(raw));
        Path snapshot = projectStorage.resolve("fingerprint-" + idHash + "-" + checksum + ".bin");
        Files.move(raw, snapshot);

        IOException error = assertThrows(IOException.class, () -> store.load(projectId, "index-1"));

        assertTrue(error.getMessage().contains("invalid fileCount"));
    }

    @Test
    void keepsProjectsIsolated(@TempDir Path root) throws Exception {
        Path project = root.resolve("project");
        createProject(project, "class App {}");
        FileProjectFingerprintSnapshotStore store = new FileProjectFingerprintSnapshotStore(root.resolve("storage"));
        UUID firstProject = UUID.randomUUID();
        UUID secondProject = UUID.randomUUID();
        store.publish(firstProject, "index-1", fingerprintService.capture(project));

        assertFalse(store.load(secondProject, "index-1").isPresent());
        assertEquals(List.of(), store.listIndexSnapshotIds(secondProject));
    }

    @Test
    void preservesLegacyM17FingerprintSnapshotsWhenM24AddsBuildDescriptors(@TempDir Path root)
            throws Exception {
        Path project = root.resolve("project");
        Files.createDirectories(project);
        Files.writeString(project.resolve("go.mod"), "module example.com/minos/m24\n");
        Files.writeString(project.resolve("main.go"), "package main\n");

        ProjectFingerprint legacy = new ProjectFingerprintService(BuildDescriptorPolicy.m17Defaults())
                .capture(project);
        ProjectFingerprint current = fingerprintService.capture(project);
        assertEquals(legacy.projectSha256(), current.projectSha256());
        assertNotEquals(legacy.buildSha256(), current.buildSha256());

        UUID projectId = UUID.randomUUID();
        Path storage = root.resolve("storage");
        FileProjectFingerprintSnapshotStore writer = new FileProjectFingerprintSnapshotStore(storage);
        writer.publish(projectId, "legacy-index", legacy);
        writer.promote(projectId, "legacy-index");

        FileProjectFingerprintSnapshotStore reader = new FileProjectFingerprintSnapshotStore(storage);
        assertEquals(legacy, reader.load(projectId, "legacy-index").orElseThrow().fingerprint());
        assertEquals(legacy, reader.loadActive(projectId).orElseThrow().fingerprint());
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void createProject(Path project, String source) throws IOException {
        Files.createDirectories(project.resolve("src"));
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        Files.writeString(project.resolve("pyproject.toml"), "[project]\nname = \"fixture\"\nversion = \"1.0.0\"\n");
        Files.writeString(project.resolve("src/App.java"), source);
    }
}
