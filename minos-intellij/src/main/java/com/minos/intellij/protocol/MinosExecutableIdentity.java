package com.minos.intellij.protocol;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

/**
 * Cheap, portable identity for a resolved MINOS executable file.
 *
 * <p>Used to detect that the binary at a previously verified path has been replaced (e.g. an
 * in-place MINOS upgrade) while the IDE process keeps running, so a cached handshake is not
 * trusted for a different binary that now sits at the same path.</p>
 */
final class MinosExecutableIdentity {

    private static final String UNRESOLVED = "unresolved";

    private MinosExecutableIdentity() {
    }

    static String describe(Path resolvedExecutable) {
        try {
            long size = Files.size(resolvedExecutable);
            FileTime modified = Files.getLastModifiedTime(resolvedExecutable);
            return size + ":" + modified.toMillis();
        } catch (IOException | RuntimeException failure) {
            return UNRESOLVED;
        }
    }
}
