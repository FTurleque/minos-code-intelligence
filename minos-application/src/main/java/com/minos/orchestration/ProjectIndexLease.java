package com.minos.orchestration;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/** Cross-JVM exclusive lease for one project's indexing lifecycle. */
public final class ProjectIndexLease implements AutoCloseable {

    private static final ConcurrentMap<Path, ReentrantLock> JVM_LOCKS = new ConcurrentHashMap<>();

    private final ReentrantLock jvmLock;
    private final FileChannel channel;
    private final FileLock fileLock;
    private boolean closed;

    private ProjectIndexLease(ReentrantLock jvmLock, FileChannel channel, FileLock fileLock) {
        this.jvmLock = jvmLock;
        this.channel = channel;
        this.fileLock = fileLock;
    }

    public static ProjectIndexLease acquire(Path minosHome, UUID projectId) throws IOException {
        Path home = Objects.requireNonNull(minosHome, "minosHome").toAbsolutePath().normalize();
        Objects.requireNonNull(projectId, "projectId");
        Path directory = home.resolve("locks").resolve("indexing");
        Files.createDirectories(directory);
        Path lockPath = directory.resolve(projectId + ".lock").toAbsolutePath().normalize();
        if (!lockPath.startsWith(directory.toAbsolutePath().normalize())) {
            throw new IOException("project indexing lock escapes MINOS lock directory");
        }
        ReentrantLock jvm = JVM_LOCKS.computeIfAbsent(lockPath, ignored -> new ReentrantLock());
        jvm.lock();
        FileChannel channel = null;
        try {
            channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock = channel.lock();
            return new ProjectIndexLease(jvm, channel, lock);
        } catch (IOException | RuntimeException exception) {
            if (channel != null) channel.close();
            jvm.unlock();
            throw exception;
        }
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        IOException failure = null;
        try {
            fileLock.release();
        } catch (IOException exception) {
            failure = exception;
        }
        try {
            channel.close();
        } catch (IOException exception) {
            if (failure == null) failure = exception; else failure.addSuppressed(exception);
        } finally {
            jvmLock.unlock();
        }
        if (failure != null) throw failure;
    }
}
