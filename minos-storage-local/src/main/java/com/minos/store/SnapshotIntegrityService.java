package com.minos.store;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** Checksum and metadata verification kept separate from snapshot encoding and file publication. */
public final class SnapshotIntegrityService {

    private static final HexFormat HEX = HexFormat.of();

    public String checksum(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        MessageDigest digest = sha256Digest();
        try (InputStream input = new DigestInputStream(Files.newInputStream(file), digest)) {
            input.transferTo(OutputStream.nullOutputStream());
        }
        return HEX.formatHex(digest.digest());
    }

    /** Preserves the historical snapshot-id hashing used in published file names. */
    public String logicalIdHash(String value) {
        Objects.requireNonNull(value, "value");
        MessageDigest digest = sha256Digest();
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            digest.update((byte) (current >>> 8));
            digest.update((byte) current);
        }
        return HEX.formatHex(digest.digest());
    }

    public void verifyChecksum(Path file, String expectedChecksum) throws IOException {
        Objects.requireNonNull(expectedChecksum, "expectedChecksum");
        if (!checksum(file).equals(expectedChecksum)) {
            throw new IOException("active symbol snapshot checksum mismatch");
        }
    }

    public void verifyMetadata(
            CodeKnowledgeSnapshot snapshot,
            UUID projectId,
            SnapshotDescriptor descriptor
    ) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(descriptor, "descriptor");
        if (!snapshot.projectId().equals(projectId)) {
            throw new IOException("active snapshot belongs to another project");
        }
        if (!snapshot.snapshotId().equals(descriptor.snapshotId())) {
            throw new IOException("active snapshot id does not match its pointer");
        }
        if (snapshot.symbols().size() != descriptor.symbolCount()) {
            throw new IOException("active snapshot symbol count does not match its pointer");
        }
        if (snapshot.occurrences().size() != descriptor.occurrenceCount()) {
            throw new IOException("active snapshot occurrence count does not match its pointer");
        }
        if (snapshot.relationships().size() != descriptor.relationshipCount()) {
            throw new IOException("active snapshot relationship count does not match its pointer");
        }
    }

    static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
