package com.minos.store;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/** Cross-JVM mutation lease shared by snapshot publication/promotion and retention. */
final class SnapshotProjectLease implements AutoCloseable {
    private static final int STRIPES = 64;
    private static final ReentrantLock[] JVM_LOCKS = locks();

    private final ReentrantLock jvmLock;
    private final FileChannel channel;
    private final FileLock fileLock;
    private boolean closed;

    private SnapshotProjectLease(ReentrantLock jvmLock, FileChannel channel, FileLock fileLock) {
        this.jvmLock = jvmLock;
        this.channel = channel;
        this.fileLock = fileLock;
    }

    static SnapshotProjectLease acquire(Path storageRoot, UUID projectId) throws IOException {
        Path directory = Objects.requireNonNull(storageRoot, "storageRoot")
                .toAbsolutePath().normalize().resolve(".snapshot-leases");
        Files.createDirectories(directory);
        Path file = directory.resolve(Objects.requireNonNull(projectId, "projectId") + ".lock");
        ReentrantLock jvmLock = JVM_LOCKS[Math.floorMod(file.hashCode(), JVM_LOCKS.length)];
        jvmLock.lock();
        FileChannel channel = null;
        try {
            channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            return new SnapshotProjectLease(jvmLock, channel, channel.lock());
        } catch (IOException | RuntimeException exception) {
            if (channel != null) channel.close();
            jvmLock.unlock();
            throw exception;
        }
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        IOException failure = null;
        try { fileLock.release(); } catch (IOException exception) { failure = exception; }
        try { channel.close(); } catch (IOException exception) {
            if (failure == null) failure = exception; else failure.addSuppressed(exception);
        } finally {
            jvmLock.unlock();
        }
        if (failure != null) throw failure;
    }

    private static ReentrantLock[] locks() {
        ReentrantLock[] locks = new ReentrantLock[STRIPES];
        for (int index = 0; index < locks.length; index++) locks[index] = new ReentrantLock();
        return locks;
    }
}
