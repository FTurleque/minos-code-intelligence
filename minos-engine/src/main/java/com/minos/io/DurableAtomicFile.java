package com.minos.io;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Objects;

/** Shared fail-closed primitive for durable local control-plane file mutations. */
public final class DurableAtomicFile {

    private DurableAtomicFile() {
    }

    /** Creates one directory hierarchy and durably publishes every newly created directory entry. */
    public static void ensureDirectory(Path directory, String label) throws IOException {
        Path target = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        String operation = requireLabel(label);
        if (Files.isDirectory(target)) return;
        Path parent = target.getParent();
        if (parent != null && !Files.isDirectory(parent)) {
            ensureDirectory(parent, operation + " parent");
        }
        try {
            Files.createDirectory(target);
        } catch (java.nio.file.FileAlreadyExistsException racedCreation) {
            if (!Files.isDirectory(target)) throw racedCreation;
            return;
        }
        try {
            forceDirectory(parent);
        } catch (IOException failure) {
            throw new CommitUncertainException(
                    operation + " directory is visible but parent durability sync failed: " + target,
                    failure);
        }
    }

    /** Publishes a new immutable file; an existing target is never replaced. */
    public static void publish(Path source, Path target, String label) throws IOException {
        move(source, target, false, requireLabel(label), DurableAtomicFile::forceDirectory);
    }

    /** Replaces a control-plane file atomically and durably. */
    public static void replace(Path source, Path target, String label) throws IOException {
        move(source, target, true, requireLabel(label), DurableAtomicFile::forceDirectory);
    }

    /** Deletes a file and makes the directory entry removal durable where supported. */
    public static boolean deleteIfExists(Path target, String label) throws IOException {
        Path normalized = Objects.requireNonNull(target, "target").toAbsolutePath().normalize();
        String operation = requireLabel(label);
        boolean deleted = Files.deleteIfExists(normalized);
        if (!deleted) return false;
        try {
            forceDirectory(normalized.getParent());
        } catch (IOException failure) {
            throw new CommitUncertainException(
                    operation + " deletion is visible but directory durability sync failed: " + normalized,
                    failure);
        }
        return true;
    }

    static void move(
            Path source,
            Path target,
            boolean replaceExisting,
            String label,
            DirectorySync directorySync
    ) throws IOException {
        Path from = Objects.requireNonNull(source, "source").toAbsolutePath().normalize();
        Path to = Objects.requireNonNull(target, "target").toAbsolutePath().normalize();
        Objects.requireNonNull(directorySync, "directorySync");
        forceFile(from);
        try {
            if (replaceExisting) {
                Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (AtomicMoveNotSupportedException unsupported) {
            throw new IOException("filesystem does not support required atomic " + label + ": " + to, unsupported);
        }
        try {
            directorySync.force(to.getParent());
        } catch (IOException failure) {
            throw new CommitUncertainException(
                    label + " committed but directory durability acknowledgement failed: " + to,
                    failure);
        }
    }

    static void forceFile(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    static void forceDirectory(Path directory) throws IOException {
        if (directory == null || windows()) return;
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (UnsupportedOperationException unsupported) {
            throw new IOException("filesystem does not support required directory durability sync: " + directory,
                    unsupported);
        }
    }

    private static String requireLabel(String label) {
        if (label == null || label.isBlank()) throw new IllegalArgumentException("label must not be blank");
        return label;
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    @FunctionalInterface
    interface DirectorySync {
        void force(Path directory) throws IOException;
    }
}
