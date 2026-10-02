package com.minos.io;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static com.minos.domain.Preconditions.requireText;

/**
 * Shared fail-closed primitive for durable local control-plane file mutations.
 *
 * <p>Readers of these files take no lease (a read never waits for a writer): a replacement is one
 * atomic rename, so a reader sees the previous or the next content, never a torn one. On Windows
 * that rename fails with a sharing violation or an access-denied error while a reader holds the
 * target open; such a replacement is retried a bounded number of times, see
 * {@link #REPLACE_ATTEMPTS_ON_WINDOWS}.</p>
 */
public final class DurableAtomicFile {

    /** Attempts of one replacement on Windows; the pauses grow linearly, about one second in all. */
    static final int REPLACE_ATTEMPTS_ON_WINDOWS = 20;
    private static final long REPLACE_PAUSE_STEP_NANOS = TimeUnit.MILLISECONDS.toNanos(5);

    private DurableAtomicFile() {
    }

    /**
     * Creates one directory hierarchy, owner-only, and durably publishes every newly created entry.
     *
     * <p>Every caller of this helper stores material derived from user code — snapshots, registry
     * metadata, index state, fingerprints, hosted control-plane records — so the directory is
     * created through {@link PrivateLocalStorage}: permissions are requested at creation rather
     * than applied afterwards, a hierarchy left world-readable by an installation that predates the
     * policy is hardened in place, and the result is verified before any caller writes into it.</p>
     */
    public static void ensureDirectory(Path directory, String label) throws IOException {
        Path target = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        String operation = requireText(label, "label");
        boolean existed = Files.isDirectory(target);
        Path parent = target.getParent();
        if (!existed && parent != null && !Files.isDirectory(parent)) {
            ensureDirectory(parent, operation + " parent");
        }
        PrivateLocalStorage.ensurePrivateDirectory(target);
        if (existed) return;
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
        move(source, target, false, requireText(label, "label"), DurableAtomicFile::forceDirectory);
    }

    /** Replaces a control-plane file atomically and durably. */
    public static void replace(Path source, Path target, String label) throws IOException {
        move(source, target, true, requireText(label, "label"), DurableAtomicFile::forceDirectory);
    }

    /** Deletes a file and makes the directory entry removal durable where supported. */
    public static boolean deleteIfExists(Path target, String label) throws IOException {
        Path normalized = Objects.requireNonNull(target, "target").toAbsolutePath().normalize();
        String operation = requireText(label, "label");
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
        move(source, target, replaceExisting, label, directorySync, Platform.SYSTEM);
    }

    static void move(
            Path source,
            Path target,
            boolean replaceExisting,
            String label,
            DirectorySync directorySync,
            Platform platform
    ) throws IOException {
        Path from = Objects.requireNonNull(source, "source").toAbsolutePath().normalize();
        Path to = Objects.requireNonNull(target, "target").toAbsolutePath().normalize();
        Objects.requireNonNull(directorySync, "directorySync");
        Objects.requireNonNull(platform, "platform");
        forceFile(from);
        atomicMove(from, to, replaceExisting, label, platform);
        try {
            directorySync.force(to.getParent());
        } catch (IOException failure) {
            throw new CommitUncertainException(
                    label + " committed but directory durability acknowledgement failed: " + to,
                    failure);
        }
    }

    private static void atomicMove(
            Path from,
            Path to,
            boolean replaceExisting,
            String label,
            Platform platform
    ) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                if (replaceExisting) {
                    platform.mover().move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    platform.mover().move(from, to, StandardCopyOption.ATOMIC_MOVE);
                }
                return;
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException("filesystem does not support required atomic " + label + ": " + to, unsupported);
            } catch (FileSystemException failure) {
                boolean retry = replaceExisting && platform.windows()
                        && heldOpenByAReader(failure) && attempt < REPLACE_ATTEMPTS_ON_WINDOWS;
                if (!retry) throw failure;
                platform.pause().pause(attempt);
            }
        }
    }

    /**
     * The failures Windows reports when the target of a replacement is open in another reader: an
     * access-denied error or a plain file-system failure (sharing violation). A missing source or
     * target, a non-empty directory and every other specific failure are final.
     */
    private static boolean heldOpenByAReader(FileSystemException failure) {
        return failure instanceof AccessDeniedException || failure.getClass() == FileSystemException.class;
    }

    private static void pauseBeforeReplaceRetry(int attempt) throws IOException {
        LockSupport.parkNanos(REPLACE_PAUSE_STEP_NANOS * attempt);
        if (Thread.interrupted()) {
            Thread.currentThread().interrupt();
            // R6: the interruption stays in the chain of causes, where the orchestration looks for it.
            throw new IOException("interrupted while retrying an atomic replacement",
                    new InterruptedException("interrupted while retrying an atomic replacement"));
        }
    }

    static void forceFile(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    /**
     * Forces the directory entry of a rename or a deletion to stable storage, where Java can.
     *
     * <p><b>Inert on Windows, by limitation and not by choice.</b> Java opens a directory only with
     * {@code FileChannel.open}, and Windows refuses that handle ({@code AccessDeniedException} for
     * {@code READ} and for {@code WRITE}, measured on JDK 24): flushing a directory needs
     * {@code CreateFile} with {@code FILE_FLAG_BACKUP_SEMANTICS} and {@code FlushFileBuffers}, which are
     * reachable only through native code (FFM or JNA), and no clean pure-Java equivalent exists. Nothing is
     * simulated here: on Windows this method does nothing.</p>
     *
     * <p>What that means for durability. The data is not at stake: {@link #forceFile} flushes the file
     * before the rename, on every platform. The rename itself is an NTFS metadata operation, and NTFS
     * journals its metadata, so after a crash the volume is consistent: the rename is either applied or
     * not, never torn, and the target is never a half-written file. What is not guaranteed, because the
     * journal tail is flushed lazily, is that a rename that already returned survives a power loss that
     * follows it closely: the previous content (or, for a publication, the absence of the entry) may be
     * what the next start sees. That is a lost last write, not corruption, and it is the same
     * observable state as a crash just before the call. The JDK's own atomic move
     * ({@code MoveFileEx} with {@code MOVEFILE_REPLACE_EXISTING}) does not request write-through either.</p>
     */
    static void forceDirectory(Path directory) throws IOException {
        if (directory == null || windows()) return;
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (UnsupportedOperationException unsupported) {
            throw new IOException("filesystem does not support required directory durability sync: " + directory,
                    unsupported);
        }
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    @FunctionalInterface
    interface DirectorySync {
        void force(Path directory) throws IOException;
    }

    /** The platform-dependent steps of a move, replaceable so the retry policy is testable anywhere. */
    record Platform(FileMover mover, boolean windows, ReplacePause pause) {
        static final Platform SYSTEM = new Platform(
                Files::move, DurableAtomicFile.windows(), DurableAtomicFile::pauseBeforeReplaceRetry);
    }

    @FunctionalInterface
    interface FileMover {
        void move(Path from, Path to, CopyOption... options) throws IOException;
    }

    @FunctionalInterface
    interface ReplacePause {
        void pause(int attempt) throws IOException;
    }
}
