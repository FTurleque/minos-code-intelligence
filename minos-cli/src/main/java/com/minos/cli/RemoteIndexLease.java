package com.minos.cli;

import com.minos.io.BoundedFileLease;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/** Cross-JVM lease covering one complete remote register/pin/index/rollback transaction. */
final class RemoteIndexLease implements AutoCloseable {
    private static final int LOCK_STRIPES = 64;
    private static final Duration ACQUIRE_TIMEOUT = Duration.ofSeconds(10);
    private static final ReentrantLock[] JVM_LOCKS = locks();

    private final BoundedFileLease lease;

    private RemoteIndexLease(BoundedFileLease lease) {
        this.lease = lease;
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
        return new RemoteIndexLease(BoundedFileLease.acquire(
                lockFile,
                jvmLock,
                ACQUIRE_TIMEOUT,
                "remote indexing lease: " + digest));
    }

    @Override
    public void close() throws IOException {
        lease.close();
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
