package com.minos.adapter.scip;

import com.minos.io.BoundedInputStream;

import java.io.DigestInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;

/** Immutable bounded copy of a SCIP artifact captured from one source handle. */
public final class ScipArtifactSnapshot implements AutoCloseable {
    private final Path path;
    private final String sha256;
    private boolean closed;

    private ScipArtifactSnapshot(Path path, String sha256) {
        this.path = path;
        this.sha256 = sha256;
    }

    public static ScipArtifactSnapshot capture(Path source, Path stagingRoot) throws IOException {
        Path input = Objects.requireNonNull(source, "source").toAbsolutePath().normalize();
        Path root = Objects.requireNonNull(stagingRoot, "stagingRoot").toAbsolutePath().normalize();
        if (Files.isSymbolicLink(input) || !Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("SCIP artifact must be a regular non-symbolic-link file");
        }
        Files.createDirectories(root);
        Path frozen = Files.createTempFile(root, "scip-import-", ".scip");
        boolean success = false;
        try {
            long expected;
            String digest;
            try (SeekableByteChannel sourceChannel = Files.newByteChannel(
                         input, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
                 OutputStream output = Files.newOutputStream(
                         frozen, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                expected = sourceChannel.size();
                long maximum = ScipIngestionLimits.DEFAULT.maxArtifactBytes();
                if (expected < 1L || expected > maximum) {
                    throw new IOException("SCIP artifact size is outside the allowed range: " + expected + "/" + maximum);
                }
                MessageDigest sha = sha256Digest();
                BoundedInputStream bounded = new BoundedInputStream(
                        Channels.newInputStream(sourceChannel), expected, "SCIP artifact snapshot");
                try (DigestInputStream stream = new DigestInputStream(bounded, sha)) {
                    stream.transferTo(output);
                }
                if (bounded.consumedBytes() != expected || sourceChannel.size() != expected) {
                    throw new IOException("SCIP artifact changed while being captured");
                }
                digest = HexFormat.of().formatHex(sha.digest());
            }
            if (!Files.isRegularFile(frozen, LinkOption.NOFOLLOW_LINKS) || Files.size(frozen) != expected) {
                throw new IOException("frozen SCIP artifact length mismatch");
            }
            success = true;
            return new ScipArtifactSnapshot(frozen, digest);
        } finally {
            if (!success) Files.deleteIfExists(frozen);
        }
    }

    public Path path() { ensureOpen(); return path; }
    public String sha256() { ensureOpen(); return sha256; }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("SCIP artifact snapshot is closed");
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        Files.deleteIfExists(path);
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
