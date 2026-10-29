package com.minos.io;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.UserPrincipal;

/**
 * The principal this process creates new objects as, read once per JVM from the owner the operating system gives a
 * file this process creates. Never read from a name or a property, which can both be set from outside.
 */
final class ProcessOwner {

    private static volatile UserPrincipal current;

    private ProcessOwner() {
    }

    /** The owner of a file this process creates, probed beside {@code near} the first time, then kept. */
    static UserPrincipal current(Path near) throws IOException {
        UserPrincipal known = current;
        if (known != null) return known;
        current = probe(near);
        return current;
    }

    /**
     * The probe could be written neither beside the checked object nor in it. The original exception names the probe's
     * absolute path: it is replaced by a message without a path, as every refusal of private storage is, keeping only
     * the kind of failure as a path-free cause.
     */
    private static IOException unverifiable(IOException failure) {
        IOException cause = failure instanceof AccessDeniedException ? new AccessDeniedException(null)
                : new IOException(failure.getClass().getSimpleName());
        return new IOException("MINOS cannot verify who owns an existing directory it cannot write to; its permissions "
                + "are unchanged. Choose a MINOS_HOME the current account can write to", cause);
    }

    static UserPrincipal probe(Path near) throws IOException {
        // The probe lives next to the object being checked, never in a publicly writable directory: the owner the
        // system gives a new file does not depend on the directory it is created in. When the parent cannot be written
        // (the parent of a MINOS home is often another account's), the checked directory itself is used.
        Path parent = near.getParent();
        Path directory = parent != null ? parent : near;
        Path probe;
        try {
            probe = Files.createTempFile(directory, "minos-owner-", ".probe");
        } catch (IOException parentNotWritable) {
            if (directory.equals(near) || !Files.isDirectory(near, LinkOption.NOFOLLOW_LINKS)) {
                throw unverifiable(parentNotWritable);
            }
            try {
                probe = Files.createTempFile(near, "minos-owner-", ".probe");
            } catch (IOException notWritableEither) {
                throw unverifiable(notWritableEither);
            }
        }
        try {
            return Files.getOwner(probe, LinkOption.NOFOLLOW_LINKS);
        } finally {
            Files.deleteIfExists(probe);
        }
    }
}
