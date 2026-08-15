package com.minos.io;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Owner-thread, cross-process file lease with one bounded deadline shared by the JVM and OS locks.
 *
 * <p>The helper deliberately owns the full acquisition and cleanup lifecycle so callers do not
 * duplicate subtle timeout, interruption and partial-acquisition handling.</p>
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

    public static BoundedFileLease acquire(
            Path lockFile,
            ReentrantLock jvmLock,
            Duration timeout,
            String description
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
            channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock fileLock = acquireFileLock(channel, deadline, label);
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
            String description
    ) throws IOException {
        FileLock lock = tryFileLock(channel);
        while (lock == null) {
            deadline.pauseBeforeRetry(description + " cross-process lease");
            lock = tryFileLock(channel);
        }
        return lock;
    }

    private static FileLock tryFileLock(FileChannel channel) throws IOException {
        try {
            return channel.tryLock();
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
