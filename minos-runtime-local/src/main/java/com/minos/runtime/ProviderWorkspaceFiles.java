package com.minos.runtime;

import com.minos.io.FileTreeOperations;
import com.minos.source.ProjectIgnoreRules;
import com.minos.source.SourceBudgetPolicy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.util.Objects;

/** Shared bounded filesystem primitives for ephemeral provider workspaces. */
final class ProviderWorkspaceFiles {

    private ProviderWorkspaceFiles() {
    }

    static void copyWorkspace(
            Path sourceRoot,
            Path targetRoot,
            SourceBudgetPolicy budgetPolicy,
            String boundary
    ) throws IOException {
        Path source = Objects.requireNonNull(sourceRoot, "sourceRoot").toRealPath();
        Path target = Objects.requireNonNull(targetRoot, "targetRoot").toAbsolutePath().normalize();
        SourceBudgetPolicy policy = Objects.requireNonNull(budgetPolicy, "budgetPolicy");
        String label = requireBoundary(boundary);
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(label + " source workspace is not a directory");
        }

        ProjectIgnoreRules ignoreRules = ProjectIgnoreRules.load(source);
        SourceBudgetPolicy.Tracker budget = policy.tracker(label);
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                budget.accountTraversalEntry();
                Path relative = source.relativize(directory);
                if (!relative.toString().isEmpty() && ignoreRules.isHardIgnored(relative)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (Files.isSymbolicLink(directory) || attributes.isSymbolicLink()) {
                    throw new IOException(label + " rejects symbolic links: " + portable(relative));
                }
                Files.createDirectories(checkedTarget(target, relative, label));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                budget.accountTraversalEntry();
                Path relative = source.relativize(file);
                if (ignoreRules.isIgnored(relative, false)) {
                    return FileVisitResult.CONTINUE;
                }
                if (Files.isSymbolicLink(file) || attributes.isSymbolicLink()) {
                    throw new IOException(label + " rejects symbolic links: " + portable(relative));
                }
                if (!attributes.isRegularFile()) {
                    throw new IOException(label + " rejects non-regular entry: " + portable(relative));
                }
                budget.accountFile();
                Path destination = checkedTarget(target, relative, label);
                Files.createDirectories(destination.getParent());
                copyBounded(file, destination, budget);
                Files.setLastModifiedTime(destination, attributes.lastModifiedTime());
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException failure) throws IOException {
                budget.accountTraversalEntry();
                throw failure;
            }
        });
    }

    static void deleteTree(Path allowedRoot, Path target, String boundary) throws IOException {
        Path root = Objects.requireNonNull(allowedRoot, "allowedRoot").toAbsolutePath().normalize();
        Path normalized = Objects.requireNonNull(target, "target").toAbsolutePath().normalize();
        String label = requireBoundary(boundary);
        if (normalized.equals(root) || !normalized.startsWith(root)) {
            throw new IOException(label + " refuses to delete outside its workspace root");
        }
        Files.walkFileTree(normalized, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                if (FileTreeOperations.isRecursableDirectory(attributes)) return FileVisitResult.CONTINUE;
                // A provider-planted Windows junction/reparse point: never descend into it. Deleting
                // the entry itself only removes the reparse point, never the content it points at.
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

    private static void copyBounded(Path source, Path target, SourceBudgetPolicy.Tracker budget) throws IOException {
        byte[] buffer = new byte[8192];
        try (InputStream input = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS);
             OutputStream output = Files.newOutputStream(
                     target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                budget.accountBytes(read);
                output.write(buffer, 0, read);
            }
        } catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(target);
            throw failure;
        }
    }

    private static Path checkedTarget(Path targetRoot, Path relative, String boundary) throws IOException {
        Path target = targetRoot.resolve(relative).normalize();
        if (!target.startsWith(targetRoot)) {
            throw new IOException(boundary + " path escapes its target root");
        }
        return target;
    }

    private static void clearReadOnly(Path path) {
        try {
            DosFileAttributeView attributes = Files.getFileAttributeView(
                    path, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes != null && attributes.readAttributes().isReadOnly()) {
                attributes.setReadOnly(false);
            }
        } catch (IOException | UnsupportedOperationException ignored) {
            // Non-DOS file systems do not need this Windows-specific cleanup.
        }
    }

    private static String requireBoundary(String boundary) {
        if (boundary == null || boundary.isBlank()) {
            throw new IllegalArgumentException("workspace boundary must not be blank");
        }
        return boundary;
    }

    private static String portable(Path path) {
        return path.toString().replace('\\', '/');
    }
}
