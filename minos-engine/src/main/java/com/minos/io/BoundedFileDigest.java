package com.minos.io;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;

/** Exact streaming digests for files that cross a trust boundary. */
public final class BoundedFileDigest {
    private BoundedFileDigest() {
    }

    public static String sha256Exact(Path file, long maximumBytes, String boundary) throws IOException {
        Path value = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        if (maximumBytes < 1L) throw new IllegalArgumentException("maximumBytes must be positive");
        if (Files.isSymbolicLink(value) || !Files.isRegularFile(value, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(label(boundary) + " must be a regular non-symbolic-link file");
        }
        try (SeekableByteChannel channel = Files.newByteChannel(
                value, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            long expectedBytes = channel.size();
            if (expectedBytes < 1L || expectedBytes > maximumBytes) {
                throw new IOException(label(boundary) + " size is outside the allowed range: "
                        + expectedBytes + "/" + maximumBytes);
            }
            MessageDigest digest = sha256();
            BoundedInputStream bounded = new BoundedInputStream(
                    Channels.newInputStream(channel), expectedBytes, label(boundary) + " digest");
            new DigestInputStream(bounded, digest).transferTo(OutputStream.nullOutputStream());
            if (bounded.consumedBytes() != expectedBytes || channel.size() != expectedBytes) {
                throw new IOException(label(boundary) + " changed while hashing");
            }
            return HexFormat.of().formatHex(digest.digest());
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String label(String value) {
        return value == null || value.isBlank() ? "file" : value;
    }
}
