package com.minos.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSymbolSnapshotStoreSymlinkTest {

    @Test
    @EnabledOnOs(OS.LINUX)
    void rejectsSymlinkedActivePointerInsteadOfTreatingItAsAuthoritative(@TempDir Path root)
            throws IOException {
        UUID projectId = UUID.randomUUID();
        FileSymbolSnapshotStore store = new FileSymbolSnapshotStore(root);
        store.publish(projectId, "snapshot-1", FileSymbolSnapshotStoreTest.symbols(projectId));

        Path projectDirectory = root.resolve(projectId.toString());
        Path activePointer = projectDirectory.resolve("active.pointer");
        Path outsidePointer = root.resolve("outside-active.pointer");
        Files.move(activePointer, outsidePointer);
        Files.createSymbolicLink(activePointer, outsidePointer.toAbsolutePath());

        IOException failure = assertThrows(IOException.class, () -> store.loadActive(projectId));

        assertTrue(failure.getMessage().contains("active snapshot pointer must be a regular file"));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void rejectsSymlinkedSnapshotPayloadBeforeChecksumOrDeserialization(@TempDir Path root)
            throws IOException {
        UUID projectId = UUID.randomUUID();
        FileSymbolSnapshotStore store = new FileSymbolSnapshotStore(root);
        store.publish(projectId, "snapshot-1", FileSymbolSnapshotStoreTest.symbols(projectId));

        Path projectDirectory = root.resolve(projectId.toString());
        Path snapshot;
        try (var files = Files.list(projectDirectory)) {
            snapshot = files
                    .filter(path -> path.getFileName().toString().endsWith(".symbols"))
                    .findFirst()
                    .orElseThrow();
        }
        Path outsideSnapshot = root.resolve("outside.symbols");
        Files.move(snapshot, outsideSnapshot);
        Files.createSymbolicLink(snapshot, outsideSnapshot.toAbsolutePath());

        IOException failure = assertThrows(IOException.class, () -> store.loadActive(projectId));

        assertTrue(failure.getMessage().contains("snapshot checksum source must be a regular file"));
    }
}
