package com.minos.incremental;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileProjectFingerprintSnapshotStoreSymlinkTest {

    private final ProjectFingerprintService fingerprints = new ProjectFingerprintService();

    @Test
    void rejectsSymlinkedStoreRoot(@TempDir Path temp) throws Exception {
        Path outside = Files.createDirectories(temp.resolve("outside"));
        Path storage = temp.resolve("fingerprints");
        if (!createSymbolicLink(storage, outside)) return;

        assertThrows(IOException.class, () -> new FileProjectFingerprintSnapshotStore(storage));
    }

    @Test
    void rejectsSymlinkedProjectDirectoryOnRead(@TempDir Path temp) throws Exception {
        Path storage = temp.resolve("fingerprints");
        FileProjectFingerprintSnapshotStore store = new FileProjectFingerprintSnapshotStore(storage);
        UUID projectId = UUID.randomUUID();
        Path outside = Files.createDirectories(temp.resolve("outside"));
        if (!createSymbolicLink(storage.resolve(projectId.toString()), outside)) return;

        IOException rejected = assertThrows(IOException.class, () -> store.loadActive(projectId));
        assertTrue(rejected.getMessage().contains("non-symlink directory"));
    }

    @Test
    void rejectsSymlinkedActivePointer(@TempDir Path temp) throws Exception {
        Fixture fixture = fixture(temp);
        Path pointer = fixture.storage().resolve(fixture.projectId().toString()).resolve("active.pointer");
        Path outside = temp.resolve("outside.pointer");
        Files.move(pointer, outside, StandardCopyOption.REPLACE_EXISTING);
        if (!createSymbolicLink(pointer, outside)) return;

        IOException rejected = assertThrows(IOException.class,
                () -> fixture.store().loadActive(fixture.projectId()));
        assertTrue(rejected.getMessage().contains("regular non-symlink"));
    }

    @Test
    void rejectsSymlinkedFingerprintPayloadAcrossActiveAndHistoryReads(@TempDir Path temp) throws Exception {
        Fixture fixture = fixture(temp);
        Path projectStorage = fixture.storage().resolve(fixture.projectId().toString());
        Path payload;
        try (var files = Files.list(projectStorage)) {
            payload = files.filter(path -> path.getFileName().toString().endsWith(".bin"))
                    .findFirst().orElseThrow();
        }
        Path outside = temp.resolve("outside.bin");
        Files.move(payload, outside, StandardCopyOption.REPLACE_EXISTING);
        if (!createSymbolicLink(payload, outside)) return;

        assertThrows(IOException.class, () -> fixture.store().loadActive(fixture.projectId()));
        assertThrows(IOException.class, () -> fixture.store().load(fixture.projectId(), "index-1"));
        assertThrows(IOException.class, () -> fixture.store().listIndexSnapshotIds(fixture.projectId()));
    }

    private Fixture fixture(Path temp) throws Exception {
        Path project = temp.resolve("project");
        Files.createDirectories(project.resolve("src"));
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        Files.writeString(project.resolve("src/App.java"), "class App {}\n");
        Path storage = temp.resolve("fingerprints");
        UUID projectId = UUID.randomUUID();
        FileProjectFingerprintSnapshotStore store = new FileProjectFingerprintSnapshotStore(storage);
        store.publish(projectId, "index-1", fingerprints.capture(project));
        store.promote(projectId, "index-1");
        return new Fixture(storage, projectId, store);
    }

    private static boolean createSymbolicLink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (UnsupportedOperationException | IOException exception) {
            return false;
        }
    }

    private record Fixture(Path storage, UUID projectId, FileProjectFingerprintSnapshotStore store) { }
}
