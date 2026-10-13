package com.minos.io;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Processus fils des tests de bail entre processus : prend le bail demandé, l'annonce sur sa sortie, le garde
 * jusqu'à ce que son entrée soit fermée ou reçoive une ligne, puis le rend. Aucune attente temporisée : le test
 * parent pilote chaque étape.
 */
public final class BoundedFileLeaseHolderChild {

    static final String HELD = "HELD";

    private BoundedFileLeaseHolderChild() {
    }

    public static void main(String[] arguments) throws Exception {
        Path lockFile = Path.of(arguments[0]);
        boolean shared = "shared".equals(arguments[1]);
        ReentrantLock jvmLock = new ReentrantLock();
        Duration timeout = Duration.ofSeconds(30);
        try (BoundedFileLease ignored = shared
                ? BoundedFileLease.acquireShared(lockFile, jvmLock, timeout, "child lease")
                : BoundedFileLease.acquire(lockFile, jvmLock, timeout, "child lease")) {
            System.out.println(HELD);
            System.out.flush();
            System.in.read();
        }
    }
}
