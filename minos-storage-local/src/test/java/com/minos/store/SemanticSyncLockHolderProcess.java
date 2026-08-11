package com.minos.store;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Subprocess entry point used by cross-JVM file-lock tests.
 * Acquires an exclusive file lock, signals "READY" on stdout, then holds the lock
 * until stdin is closed (parent signals exit).
 */
public final class SemanticSyncLockHolderProcess {

    public static void main(String[] args) throws Exception {
        Path lockFile = Path.of(args[0]);
        Files.createDirectories(lockFile.getParent());
        try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            System.out.println("READY");
            System.out.flush();
            System.in.read(); // block until parent closes stdin — signal to release lock
        }
    }
}
