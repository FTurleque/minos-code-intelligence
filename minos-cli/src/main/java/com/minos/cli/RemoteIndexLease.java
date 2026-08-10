package com.minos.cli;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/** Cross-JVM lease covering one complete remote register/pin/index/rollback transaction. */
final class RemoteIndexLease implements AutoCloseable {
    private static final int LOCK_STRIPES = 64;
    private static final ReentrantLock[] JVM_LOCKS = locks();

    private final ReentrantLock jvmLock;
    private final FileChannel channel;
    private final FileLock fileLock;
    private boolean closed;

    private RemoteIndexLease(ReentrantLock jvmLock, FileChannel channel, FileLock fileLock) {
        this.jvmLock = jvmLock;
        this.channel = channel;
        this.fileLock = fileLock;
    }

    static RemoteIndexLease acquire(Path minosHome, String sourceIdentity) throws IOException {
        Path home = Objects.requireNonNull(minosHome, "minosHome").toAbsolutePath().normalize();
        if (sourceIdentity == null || sourceIdentity.isBlank()) {
            throw new IllegalArgumentException("sourceIdentity must not be blank");
        }
        Path directory = home.resolve("remote-index-leases");
        Files.createDirectories(directory);
        String digest = sha256(sourceIdentity);
        Path lockFile = directory.resolve(digest + ".lock");
        ReentrantLock jvmLock = JVM_LOCKS[Math.floorMod(lockFile.hashCode(), JVM_LOCKS.length)];
        jvmLock.lock();
        FileChannel channel = null;
        try {
            channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock fileLock = channel.lock();
            return new RemoteIndexLease(jvmLock, channel, fileLock);
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
        try {
            fileLock.release();
        } catch (IOException exception) {
            failure = exception;
        }
        try {
            channel.close();
        } catch (IOException exception) {
            if (failure == null) failure = exception;
            else failure.addSuppressed(exception);
        } finally {
            jvmLock.unlock();
        }
        if (failure != null) throw failure;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static ReentrantLock[] locks() {
        ReentrantLock[] locks = new ReentrantLock[LOCK_STRIPES];
        for (int index = 0; index < locks.length; index++) locks[index] = new ReentrantLock();
        return locks;
    }
}
