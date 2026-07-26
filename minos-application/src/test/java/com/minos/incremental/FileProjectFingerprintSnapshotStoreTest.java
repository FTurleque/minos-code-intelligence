package com.minos.incremental;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileProjectFingerprintSnapshotStoreTest {

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

    private static void createProject(Path project, String source) throws IOException {
        Files.createDirectories(project.resolve("src"));
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        Files.writeString(project.resolve("pyproject.toml"), "[project]\nname = \"fixture\"\nversion = \"1.0.0\"\n");
        Files.writeString(project.resolve("src/App.java"), source);
    }
}
