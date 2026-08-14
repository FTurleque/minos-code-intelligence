package com.minos.store;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Owns snapshot file-system layout and atomic file publication, but not binary encoding. */
public final class SnapshotRepository {

    private final Path storageRoot;

    public SnapshotRepository(Path storageRoot) throws IOException {
        this.storageRoot = Objects.requireNonNull(storageRoot, "storageRoot")
                .toAbsolutePath()
                .normalize();
        Files.createDirectories(this.storageRoot);
    }

    public Path storageRoot() {
        return storageRoot;
    }

    public Path projectDirectory(UUID projectId) {
        return storageRoot.resolve(Objects.requireNonNull(projectId, "projectId").toString());
    }

    public Path ensureProjectDirectory(UUID projectId) throws IOException {
        Path directory = projectDirectory(projectId);
        Files.createDirectories(directory);
        return directory;
    }

    public Path createTemporarySnapshot(UUID projectId) throws IOException {
        return Files.createTempFile(ensureProjectDirectory(projectId), ".snapshot-", ".tmp");
    }

    public Path createTemporaryPointer(UUID projectId) throws IOException {
        return Files.createTempFile(ensureProjectDirectory(projectId), ".active-", ".tmp");
    }

    public Path publishSnapshot(UUID projectId, String fileName, Path temporary) throws IOException {
        Path target = resolveSnapshotFile(projectId, fileName);
        moveAtomically(temporary, target);
        return target;
    }

    public void replaceActivePointer(UUID projectId, Path temporary, String activeFileName) throws IOException {
        Path target = ensureProjectDirectory(projectId).resolve(activeFileName);
        moveAtomically(temporary, target);
    }

    public Path resolveSnapshotFile(UUID projectId, String fileName) throws IOException {
        Path projectDirectory = projectDirectory(projectId);
        Path relative;
        try {
            relative = Path.of(fileName);
        } catch (InvalidPathException exception) {
            throw new IOException("invalid active symbol snapshot file name", exception);
        }
        if (relative.isAbsolute() || relative.getNameCount() != 1) {
            throw new IOException("invalid active symbol snapshot file name");
        }
        Path resolved = projectDirectory.resolve(relative).normalize();
        if (!Objects.equals(resolved.getParent(), projectDirectory)) {
            throw new IOException("active symbol snapshot escapes its project directory");
        }
        return resolved;
    }

    public List<Path> listSnapshotFiles(UUID projectId) throws IOException {
        Path directory = projectDirectory(projectId);
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (var files = Files.list(directory)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.endsWith(".symbols") || name.endsWith(".knowledge");
                    })
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
    }

    static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException(
                    "atomic file replacement is required for MINOS snapshot durability: "
                            + source + " -> " + target,
                    exception);
        }
    }
}
