package com.minos.io;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.util.Objects;

/** Streaming, no-follow operations for caller-confined filesystem trees. */
public final class FileTreeOperations {
    private FileTreeOperations() {
    }

    /** Deletes a tree without following symlinks or materializing every path in memory. */
    public static void deleteRecursively(Path target) throws IOException {
        Path normalized = Objects.requireNonNull(target, "target").toAbsolutePath().normalize();
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(normalized, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                if (isRecursableDirectory(attributes)) return FileVisitResult.CONTINUE;
                // A Windows junction/reparse point, or anything else masquerading as a directory:
                // never descend into it. Deleting the entry itself only removes the reparse point,
                // never the content it points at.
                clearReadOnly(directory);
                Files.deleteIfExists(directory);
                return FileVisitResult.SKIP_SUBTREE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                clearReadOnly(file);
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) throw failure;
                clearReadOnly(directory);
                Files.deleteIfExists(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Windows refuses to delete a read-only entry (a cloned pack file, for one); the attribute is not a security control. */
    private static void clearReadOnly(Path path) {
        try {
            DosFileAttributeView attributes = Files.getFileAttributeView(
                    path, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes != null && attributes.readAttributes().isReadOnly()) attributes.setReadOnly(false);
        } catch (IOException | UnsupportedOperationException ignored) {
            // Non-DOS file systems do not need this Windows-specific cleanup.
        }
    }

    /**
     * True only for a directory entry safe to recurse into during a no-follow tree walk.
     *
     * <p>On Windows, {@link BasicFileAttributes} reports an NTFS junction (or other reparse-point
     * directory) as {@code isDirectory()=true} without {@code isSymbolicLink()=true} — unlike a
     * symlink, the JDK never routes it to {@code visitFile}, so a plain {@code isDirectory()} check
     * alone would silently walk through the reparse point into whatever it targets, entirely
     * outside the tree the caller authorized. Empirically, the JDK also sets {@code isOther()=true}
     * for such an entry, a combination a plain directory never produces; that is the signal used
     * here to reject it.</p>
     */
    public static boolean isRecursableDirectory(BasicFileAttributes attributes) {
        Objects.requireNonNull(attributes, "attributes");
        return attributes.isDirectory() && !attributes.isSymbolicLink() && !attributes.isOther();
    }
}
