package com.minos.io;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Owner-thread, cross-process file lease with one bounded deadline shared by the JVM and OS locks.
 *
 * <p>The helper deliberately owns the full acquisition and cleanup lifecycle so callers do not
 * duplicate subtle timeout, interruption and partial-acquisition handling. Lock files are also part
 * of the local trust boundary: a pre-existing symlink must never redirect two logical leases onto
 * different physical files, so every lock file is owner-only and opened with {@link
 * LinkOption#NOFOLLOW_LINKS}.</p>
 *
 * <p>A lease is taken by a <strong>writer</strong> ({@link #acquire}: exclusive, the lock file is created
 * and hardened when needed) or by a <strong>reader</strong> ({@link #acquireShared}: shared with the other
 * readers, and nothing is created, hardened or opened for writing, so a reader works on a storage it
 * cannot write, such as a read-only mount). A writer and a reader exclude each other.</p>
 */
public final class BoundedFileLease implements AutoCloseable {

    private final ReentrantLock jvmLock;
    private final FileChannel channel;
    private final FileLock fileLock;
    private final Thread owner;
    private boolean closed;

    private BoundedFileLease(ReentrantLock jvmLock, FileChannel channel, FileLock fileLock) {
        this.jvmLock = jvmLock;
        this.channel = channel;
        this.fileLock = fileLock;
        this.owner = Thread.currentThread();
    }

    /** Acquires the lease for a writer: exclusive, the lock file is created and hardened when needed. */
    public static BoundedFileLease acquire(
            Path lockFile,
            ReentrantLock jvmLock,
            Duration timeout,
            String description
    ) throws IOException {
        return acquire(lockFile, jvmLock, timeout, description, false);
    }

    /**
     * Acquires the lease for a reader. The existing lock file is verified as a writer verifies it
     * (owner-only regular file, never a link) but is not modified: one that fails the check is refused,
     * not repaired. It is opened for reading and locked in shared mode, which excludes every writer
     * and no reader of another process.
     *
     * <p>When the lock file does not exist, no writer has held this lease yet (a writer creates the file
     * before anything else): the reader takes the JVM lock only and creates nothing. A writer of another
     * process may then start while such a reader is still reading; {@link #readShared} is the way to read
     * that closes this window, and callers should prefer it.</p>
     */
    public static BoundedFileLease acquireShared(
            Path lockFile,
            ReentrantLock jvmLock,
            Duration timeout,
            String description
    ) throws IOException {
        return acquire(lockFile, jvmLock, timeout, description, true);
    }

    /**
     * Runs {@code read} under a reader's lease and returns its result.
     *
     * <p>When the read ran without an OS lock because the lock file did not exist, and the file exists
     * once the read is done, a writer started in the meantime and the result may straddle its changes:
     * the read is run again, this time under the shared OS lock that excludes that writer. When the file
     * is still absent, no writer changed anything before the read ended (a writer creates the file before
     * anything else), so the first result stands. {@code read} must therefore be free of side effects.</p>
     */
    public static <T> T readShared(
            Path lockFile,
            ReentrantLock jvmLock,
            Duration timeout,
            String description,
            LeasedRead<T> read
    ) throws IOException {
        Objects.requireNonNull(read, "read");
        Path file = Objects.requireNonNull(lockFile, "lockFile").toAbsolutePath().normalize();
        T result;
        try (BoundedFileLease lease = acquireShared(file, jvmLock, timeout, description)) {
            result = read.run();
            if (lease.channel != null) return result;
        }
        if (lockFileIsAbsent(file, requireDescription(description))) return result;
        try (BoundedFileLease ignored = acquireShared(file, jvmLock, timeout, description)) {
            return read.run();
        }
    }

    /** A read made under a reader's lease; it may run twice (see {@link #readShared}). */
    @FunctionalInterface
    public interface LeasedRead<T> {
        T run() throws IOException;
    }

    private static BoundedFileLease acquire(
            Path lockFile,
            ReentrantLock jvmLock,
            Duration timeout,
            String description,
            boolean shared
    ) throws IOException {
        Path file = Objects.requireNonNull(lockFile, "lockFile").toAbsolutePath().normalize();
        ReentrantLock localLock = Objects.requireNonNull(jvmLock, "jvmLock");
        String label = requireDescription(description);
        LeaseDeadline deadline = LeaseDeadline.after(timeout);
        boolean localAcquired = false;
        FileChannel channel = null;
        try {
            acquireJvmLock(localLock, deadline, label);
            localAcquired = true;
            if (shared && lockFileIsAbsent(file, label)) {
                return new BoundedFileLease(localLock, null, null);
            }
            channel = shared ? openExistingLockChannel(file, label) : openPrivateLockChannel(file, label);
            FileLock fileLock = acquireFileLock(channel, deadline, label, shared);
            return new BoundedFileLease(localLock, channel, fileLock);
        } catch (IOException | RuntimeException failure) {
            IOException cleanupFailure = closeQuietly(channel);
            if (localAcquired) localLock.unlock();
            if (cleanupFailure != null) failure.addSuppressed(cleanupFailure);
            throw failure;
        }
    }

    @Override
    public void close() throws IOException {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("bounded file lease must be released by its owner thread");
        }
        if (closed) return;
        closed = true;
        if (channel == null) {
            // A reader that found no lock file holds the JVM lock only.
            jvmLock.unlock();
            return;
        }
        IOException failure = releaseFileLock();
        try {
            channel.close();
        } catch (IOException closeFailure) {
            failure = combine(failure, closeFailure);
        } finally {
            jvmLock.unlock();
        }
        if (failure != null) throw failure;
    }

    private IOException releaseFileLock() {
        try {
            fileLock.release();
            return null;
        } catch (IOException failure) {
            return failure;
        }
    }

    /**
     * Opens, for locking, the owner-only regular lock file {@code file}, creating it when absent.
     *
     * <p>This is the single way a lock file is opened in the repository: a pre-existing file is
     * hardened in place, a symbolic link or special object is refused, and the channel itself is
     * opened with {@link LinkOption#NOFOLLOW_LINKS}, so a link swapped in after the preparation is
     * still not followed. The message never carries the path.</p>
     */
    static FileChannel openPrivateLockChannel(Path file, String description) throws IOException {
        preparePrivateLockFile(file, description);
        return FileChannel.open(file, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * Opens, for a reader, the lock file {@code file} that already exists: verified, never modified,
     * opened for reading with {@link LinkOption#NOFOLLOW_LINKS}. No message carries the path.
     */
    private static FileChannel openExistingLockChannel(Path file, String description) throws IOException {
        PrivateLocalStorage.verifyPrivateFile(file);
        try {
            return FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException unopenable) {
            // The original message is the absolute path: only its class is kept.
            throw new IOException(description + " lock file cannot be opened for reading: "
                    + unopenable.getClass().getSimpleName());
        }
    }

    /**
     * Whether the lock file does not exist. Fails closed: only a missing file is "absent"; a lock file
     * that cannot be inspected is an error, never a reason to read without the cross-process lock.
     */
    private static boolean lockFileIsAbsent(Path file, String description) throws IOException {
        try {
            Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return false;
        } catch (NoSuchFileException absent) {
            return true;
        } catch (IOException uninspectable) {
            throw new IOException(description + " lock file cannot be inspected: "
                    + uninspectable.getClass().getSimpleName());
        }
    }

    private static void preparePrivateLockFile(Path file, String description) throws IOException {
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            PrivateLocalStorage.hardenExistingFile(file);
            return;
        }
        try {
            PrivateLocalStorage.createPrivateFile(file);
        } catch (FileAlreadyExistsException concurrentCreate) {
            try {
                PrivateLocalStorage.hardenExistingFile(file);
            } catch (IOException unsafeWinner) {
                unsafeWinner.addSuppressed(concurrentCreate);
                throw unsafeWinner;
            }
        }
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(description + " lock file is not a regular non-symlink file");
        }
    }

    private static void acquireJvmLock(ReentrantLock lock, LeaseDeadline deadline, String description) throws IOException {
        try {
            if (!lock.tryLock(deadline.remainingNanos(), TimeUnit.NANOSECONDS)) {
                throw deadline.timeout(description + " JVM lease");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while waiting for " + description + " JVM lease", interrupted);
        }
    }

    private static FileLock acquireFileLock(
            FileChannel channel,
            LeaseDeadline deadline,
            String description,
            boolean shared
    ) throws IOException {
        FileLock lock = tryFileLock(channel, shared);
        while (lock == null) {
            deadline.pauseBeforeRetry(description + " cross-process lease");
            lock = tryFileLock(channel, shared);
        }
        return lock;
    }

    private static FileLock tryFileLock(FileChannel channel, boolean shared) throws IOException {
        try {
            // Both modes cover the whole file, so a shared holder and an exclusive one exclude each other.
            return shared ? channel.tryLock(0L, Long.MAX_VALUE, true) : channel.tryLock();
        } catch (OverlappingFileLockException unavailableInThisJvm) {
            return null;
        }
    }

    private static IOException closeQuietly(FileChannel channel) {
        if (channel == null) return null;
        try {
            channel.close();
            return null;
        } catch (IOException failure) {
            return failure;
        }
    }

    private static IOException combine(IOException current, IOException additional) {
        if (current == null) return additional;
        current.addSuppressed(additional);
        return current;
    }

    private static String requireDescription(String description) {
        String value = Objects.requireNonNull(description, "description").trim();
        if (value.isEmpty()) throw new IllegalArgumentException("description must not be blank");
        return value;
    }
}
