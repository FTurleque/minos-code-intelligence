package com.minos.storage.codec;

import com.minos.io.PrivateLocalStorage;
import com.minos.io.ConfinedFileOpener;
import com.minos.domain.Relationship;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolOccurrence;
import com.minos.io.BoundedInputStream;
import com.minos.io.BoundedOutputStream;
import com.minos.io.Sha256;
import com.minos.storage.codec.SnapshotEncodingSink.StreamSink;
import com.minos.storage.codec.SnapshotStringEncoding.UnpairedSurrogateException;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.SymbolSnapshot;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Framing of the explicit v1/v2/v3 snapshot formats: header, entity counts, persisted ceiling, confined and bounded
 * file access, single-pass authenticated reads and exact encoded sizes.
 *
 * <p>The entity layout lives in {@link SnapshotEntityCodec}, the string encodings in {@link SnapshotStringEncoding}
 * and {@link SnapshotEncodingSink}, the bounded decoding pass in {@link SnapshotInput}. Header and UTF-16 string
 * framing delegate to {@link SnapshotBinaryPrimitives}, which the active snapshot pointer of the local backend also
 * uses. They must stay a single implementation: a divergence between the two would silently produce pointer files
 * this package can no longer read back.</p>
 */
final class SnapshotBinaryCodecSupport {

    static final int FORMAT_VERSION_V1 = 1;
    static final int FORMAT_VERSION_V2 = 2;
    /** Same layout as V2; strings are UTF-8 (length in bytes) instead of UTF-16 code units (ADR 0046). */
    static final int FORMAT_VERSION_V3 = 3;

    private static final int SNAPSHOT_MAGIC = 0x4D4E5359;
    private static final int MAX_SYMBOLS = 10_000_000;
    private static final int MAX_OCCURRENCES = 100_000_000;
    private static final int MAX_RELATIONSHIPS = 100_000_000;
    /**
     * Production ceiling of one persisted snapshot: the write is refused above it and the read as well, so a
     * snapshot published under this ceiling is unreadable once it is lowered.
     */
    static final long MAX_PERSISTED_SNAPSHOT_BYTES = 400L * 1024L * 1024L;
    private static final HexFormat HEX = HexFormat.of();
    private static final String SYMBOL_SNAPSHOT_LABEL = "symbol snapshot";
    private static final String KNOWLEDGE_SNAPSHOT_LABEL = "knowledge snapshot";

    private SnapshotBinaryCodecSupport() {
    }

    private static int initialCapacity(int declaredCount) {
        // Counts describe protocol limits, not a trusted heap-allocation request. Grow incrementally.
        return Math.min(Math.max(0, declaredCount), 16_384);
    }

    static String writeSymbolSnapshotV1(Path file, SymbolSnapshot snapshot, long maxPersistedBytes) throws IOException {
        MessageDigest digest = Sha256.newDigest();
        try (OutputStream fileOutput = PrivateLocalStorage.openPrivateOutputStream(file);
             DigestOutputStream digestOutput = new DigestOutputStream(fileOutput, digest);
             BoundedOutputStream boundedOutput = new BoundedOutputStream(
                     digestOutput, maxPersistedBytes, SYMBOL_SNAPSHOT_LABEL);
             DataOutputStream output = new DataOutputStream(new BufferedOutputStream(boundedOutput))) {
            writeSymbolSnapshotV1Body(new StreamSink(output, SnapshotStringEncoding.UTF16_CHARS), snapshot);
        }
        requireSnapshotFileSize(file, maxPersistedBytes);
        return HEX.formatHex(digest.digest());
    }

    private static void writeSymbolSnapshotV1Body(SnapshotEncodingSink output, SymbolSnapshot snapshot)
            throws IOException {
        output.writeInt(SNAPSHOT_MAGIC);
        output.writeInt(FORMAT_VERSION_V1);
        output.writeLong(snapshot.projectId().getMostSignificantBits());
        output.writeLong(snapshot.projectId().getLeastSignificantBits());
        output.writeString(snapshot.snapshotId());
        output.writeInt(snapshot.symbols().size());
        for (Symbol symbol : snapshot.symbols()) {
            SnapshotEntityCodec.writeSymbol(output, symbol);
        }
    }

    /** Exact length of {@link #writeSymbolSnapshotV1}'s output: the same traversal, counted instead of written. */
    static long encodedSymbolSnapshotV1Size(SymbolSnapshot snapshot) throws IOException {
        SnapshotEncodingSinkCounter counter = new SnapshotEncodingSinkCounter(SnapshotStringEncoding.UTF16_CHARS);
        writeSymbolSnapshotV1Body(counter, snapshot);
        return counter.bytes();
    }

    /** Exact length of {@link #writeKnowledgeSnapshotV2}'s output: the same traversal, counted instead of written. */
    static long encodedKnowledgeSnapshotV2Size(CodeKnowledgeSnapshot snapshot) throws IOException {
        SnapshotEncodingSinkCounter counter = new SnapshotEncodingSinkCounter(SnapshotStringEncoding.UTF16_CHARS);
        writeKnowledgeSnapshotBody(counter, snapshot, FORMAT_VERSION_V2);
        return counter.bytes();
    }

    /**
     * Exact length of {@link #writeKnowledgeSnapshotV3}'s output, or {@code -1} when a string holds an unpaired
     * surrogate: such a snapshot has no exact UTF-8 form and is written in V2, which preserves it.
     */
    static long encodedKnowledgeSnapshotV3Size(CodeKnowledgeSnapshot snapshot) throws IOException {
        SnapshotEncodingSinkCounter counter = new SnapshotEncodingSinkCounter(SnapshotStringEncoding.UTF8);
        try {
            writeKnowledgeSnapshotBody(counter, snapshot, FORMAT_VERSION_V3);
        } catch (UnpairedSurrogateException unencodable) {
            return -1L;
        }
        return counter.bytes();
    }

    static String writeKnowledgeSnapshotV3(Path file, CodeKnowledgeSnapshot snapshot, long maxPersistedBytes)
            throws IOException {
        MessageDigest digest = Sha256.newDigest();
        try (OutputStream fileOutput = PrivateLocalStorage.openPrivateOutputStream(file);
             DigestOutputStream digestOutput = new DigestOutputStream(fileOutput, digest);
             BoundedOutputStream boundedOutput = new BoundedOutputStream(
                     digestOutput, maxPersistedBytes, KNOWLEDGE_SNAPSHOT_LABEL);
             DataOutputStream output = new DataOutputStream(new BufferedOutputStream(boundedOutput))) {
            writeKnowledgeSnapshotBody(new StreamSink(output, SnapshotStringEncoding.UTF8), snapshot, FORMAT_VERSION_V3);
        }
        requireSnapshotFileSize(file, maxPersistedBytes);
        return HEX.formatHex(digest.digest());
    }

    static CodeKnowledgeSnapshot readKnowledgeSnapshotV3(Path file, long maxPersistedBytes) throws IOException {
        requireSnapshotFileSize(file, maxPersistedBytes);
        try (BoundedInputStream boundedInput = new BoundedInputStream(
                     ConfinedFileOpener.openRegularFileNoFollow(file),
                     maxPersistedBytes,
                     KNOWLEDGE_SNAPSHOT_LABEL);
             DataInputStream input = new DataInputStream(new BufferedInputStream(boundedInput))) {
            return readKnowledgeSnapshotBody(new SnapshotInput(input, SnapshotStringEncoding.UTF8), FORMAT_VERSION_V3);
        } catch (EOFException exception) {
            throw new IOException("truncated knowledge snapshot", exception);
        }
    }

    /** Decodes and authenticates a V3 knowledge snapshot file in a single pass over its bytes. */
    static CodeKnowledgeSnapshot readVerifiedKnowledgeSnapshotV3(Path file, String expectedSha256, long maxPersistedBytes)
            throws IOException {
        requireSnapshotFileSize(file, maxPersistedBytes);
        try (InputStream source = ConfinedFileOpener.openRegularFileNoFollow(file)) {
            return readVerifiedKnowledgeSnapshotV3(source, expectedSha256, maxPersistedBytes);
        }
    }

    static CodeKnowledgeSnapshot readVerifiedKnowledgeSnapshotV3(
            InputStream source, String expectedSha256, long maxPersistedBytes) throws IOException {
        return readVerifiedKnowledgeSnapshot(
                source, expectedSha256, SnapshotStringEncoding.UTF8, FORMAT_VERSION_V3, maxPersistedBytes);
    }

    /** Decodes and authenticates a V2 knowledge snapshot file in a single pass over its bytes. */
    static CodeKnowledgeSnapshot readVerifiedKnowledgeSnapshotV2(Path file, String expectedSha256, long maxPersistedBytes)
            throws IOException {
        requireSnapshotFileSize(file, maxPersistedBytes);
        try (InputStream source = ConfinedFileOpener.openRegularFileNoFollow(file)) {
            return readVerifiedKnowledgeSnapshotV2(source, expectedSha256, maxPersistedBytes);
        }
    }

    static CodeKnowledgeSnapshot readVerifiedKnowledgeSnapshotV2(
            InputStream source, String expectedSha256, long maxPersistedBytes) throws IOException {
        return readVerifiedKnowledgeSnapshot(
                source, expectedSha256, SnapshotStringEncoding.UTF16_CHARS, FORMAT_VERSION_V2, maxPersistedBytes);
    }

    /**
     * Decodes {@code source} while hashing it, then reads it to its end and compares the SHA-256 before
     * anything is returned. The decoder therefore sees bytes that are not authenticated yet, so nothing it
     * produced leaves this method on a mismatch: altered, truncated or extended content is reported as the
     * checksum mismatch it is, whatever the decoder made of it. A decoding failure of authentic content is
     * rethrown unchanged.
     */
    private static CodeKnowledgeSnapshot readVerifiedKnowledgeSnapshot(
            InputStream source, String expectedSha256, SnapshotStringEncoding encoding, int version,
            long maxPersistedBytes) throws IOException {
        MessageDigest digest = Sha256.newDigest();
        try (BoundedInputStream boundedInput = new BoundedInputStream(
                     new DigestInputStream(source, digest), maxPersistedBytes, KNOWLEDGE_SNAPSHOT_LABEL);
             DataInputStream input = new DataInputStream(new BufferedInputStream(boundedInput))) {
            CodeKnowledgeSnapshot decoded = null;
            IOException invalid = null;
            RuntimeException defect = null;
            try {
                decoded = readKnowledgeSnapshotBody(new SnapshotInput(input, encoding), version);
            } catch (EOFException exception) {
                invalid = new IOException("truncated knowledge snapshot", exception);
            } catch (IOException exception) {
                invalid = exception;
            } catch (RuntimeException exception) {
                defect = exception;
            }
            input.transferTo(OutputStream.nullOutputStream());
            SnapshotIntegrityService.requireChecksum(HEX.formatHex(digest.digest()), expectedSha256);
            if (invalid != null) throw invalid;
            if (defect != null) throw defect;
            return decoded;
        }
    }

    /**
     * Format version declared by the first eight bytes of a snapshot payload, or {@code -1} when they are not a
     * snapshot header (too short or another magic): the caller then estimates with the cautious amplification and
     * the decoder refuses the payload.
     */
    static int headerFormatVersion(byte[] header) {
        if (header == null || header.length < 8) return -1;
        int magic = ((header[0] & 0xFF) << 24) | ((header[1] & 0xFF) << 16) | ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
        if (magic != SNAPSHOT_MAGIC) return -1;
        return ((header[4] & 0xFF) << 24) | ((header[5] & 0xFF) << 16) | ((header[6] & 0xFF) << 8) | (header[7] & 0xFF);
    }

    /** Format version declared by a snapshot file's header, whatever the format, without decoding the body. */
    static int readFormatVersion(Path file, long maxPersistedBytes) throws IOException {
        requireSnapshotFileSize(file, maxPersistedBytes);
        // Unbuffered: exactly the eight header bytes are read, never the body.
        try (DataInputStream input = new DataInputStream(ConfinedFileOpener.openRegularFileNoFollow(file))) {
            return SnapshotBinaryPrimitives.readHeaderVersion(input, SNAPSHOT_MAGIC, "snapshot");
        } catch (EOFException exception) {
            throw new IOException("truncated snapshot header", exception);
        }
    }

    /**
     * Refuses, before any byte is written, a snapshot whose encoding would exceed the persisted ceiling.
     * The message carries only sizes, never a path, so it is safe to surface publicly.
     */
    static long requirePersistable(long encodedBytes, long maxPersistedBytes) throws IOException {
        if (encodedBytes > maxPersistedBytes) {
            throw new IOException("knowledge snapshot is too large to persist: " + encodedBytes
                    + " encoded bytes exceed the " + maxPersistedBytes
                    + "-byte limit (" + SnapshotLimits.describeMebibytes(maxPersistedBytes) + "); nothing was written");
        }
        return encodedBytes;
    }

    static SymbolSnapshot readSymbolSnapshotV1(Path file, long maxPersistedBytes) throws IOException {
        requireSnapshotFileSize(file, maxPersistedBytes);
        try (BoundedInputStream boundedInput = new BoundedInputStream(
                     ConfinedFileOpener.openRegularFileNoFollow(file),
                     maxPersistedBytes,
                     SYMBOL_SNAPSHOT_LABEL);
             DataInputStream stream = new DataInputStream(new BufferedInputStream(boundedInput))) {
            SnapshotInput input = new SnapshotInput(stream, SnapshotStringEncoding.UTF16_CHARS);
            requireHeader(input, FORMAT_VERSION_V1, SYMBOL_SNAPSHOT_LABEL);
            UUID projectId = new UUID(input.readLong(), input.readLong());
            String snapshotId = input.readRequiredString("snapshotId");
            int symbolCount = input.readCount(MAX_SYMBOLS, "symbol count");
            List<Symbol> symbols = new ArrayList<>(initialCapacity(symbolCount));
            for (int index = 0; index < symbolCount; index++) {
                symbols.add(SnapshotEntityCodec.readSymbol(input));
            }
            if (input.read() != -1) {
                throw new IOException("unexpected trailing data in symbol snapshot");
            }
            try {
                return new SymbolSnapshot(projectId, snapshotId, symbols);
            } catch (IllegalArgumentException exception) {
                throw new IOException("invalid symbol snapshot: " + exception.getMessage(), exception);
            }
        } catch (EOFException exception) {
            throw new IOException("truncated symbol snapshot", exception);
        }
    }

    static CodeKnowledgeSnapshot fromLegacy(SymbolSnapshot snapshot) {
        return new CodeKnowledgeSnapshot(
                snapshot.projectId(),
                snapshot.snapshotId(),
                snapshot.symbols(),
                List.of(),
                List.of()
        );
    }

    static String writeKnowledgeSnapshotV2(Path file, CodeKnowledgeSnapshot snapshot, long maxPersistedBytes)
            throws IOException {
        MessageDigest digest = Sha256.newDigest();
        try (OutputStream fileOutput = PrivateLocalStorage.openPrivateOutputStream(file);
             DigestOutputStream digestOutput = new DigestOutputStream(fileOutput, digest);
             BoundedOutputStream boundedOutput = new BoundedOutputStream(
                     digestOutput, maxPersistedBytes, KNOWLEDGE_SNAPSHOT_LABEL);
             DataOutputStream output = new DataOutputStream(new BufferedOutputStream(boundedOutput))) {
            writeKnowledgeSnapshotBody(
                    new StreamSink(output, SnapshotStringEncoding.UTF16_CHARS), snapshot, FORMAT_VERSION_V2);
        }
        requireSnapshotFileSize(file, maxPersistedBytes);
        return HEX.formatHex(digest.digest());
    }

    static byte[] writeKnowledgeSnapshotV2ToBytes(CodeKnowledgeSnapshot snapshot, long maxPersistedBytes)
            throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (BoundedOutputStream boundedOutput = new BoundedOutputStream(
                     baos, maxPersistedBytes, KNOWLEDGE_SNAPSHOT_LABEL);
             DataOutputStream output = new DataOutputStream(new BufferedOutputStream(boundedOutput))) {
            writeKnowledgeSnapshotBody(
                    new StreamSink(output, SnapshotStringEncoding.UTF16_CHARS), snapshot, FORMAT_VERSION_V2);
        }
        byte[] payload = baos.toByteArray();
        if (payload.length > maxPersistedBytes) {
            throw new IOException("knowledge snapshot payload exceeds persisted byte limit: " + payload.length);
        }
        return payload;
    }

    private static void writeKnowledgeSnapshotBody(SnapshotEncodingSink output, CodeKnowledgeSnapshot snapshot,
                                                   int version) throws IOException {
        output.writeInt(SNAPSHOT_MAGIC);
        output.writeInt(version);
        output.writeLong(snapshot.projectId().getMostSignificantBits());
        output.writeLong(snapshot.projectId().getLeastSignificantBits());
        output.writeString(snapshot.snapshotId());
        output.writeInt(snapshot.symbols().size());
        for (Symbol symbol : snapshot.symbols()) {
            SnapshotEntityCodec.writeSymbol(output, symbol);
        }
        output.writeInt(snapshot.occurrences().size());
        for (SymbolOccurrence occurrence : snapshot.occurrences()) {
            SnapshotEntityCodec.writeOccurrence(output, occurrence);
        }
        output.writeInt(snapshot.relationships().size());
        for (Relationship relationship : snapshot.relationships()) {
            SnapshotEntityCodec.writeRelationship(output, relationship);
        }
    }

    static CodeKnowledgeSnapshot readKnowledgeSnapshotV2(Path file, long maxPersistedBytes) throws IOException {
        requireSnapshotFileSize(file, maxPersistedBytes);
        try (BoundedInputStream boundedInput = new BoundedInputStream(
                     ConfinedFileOpener.openRegularFileNoFollow(file),
                     maxPersistedBytes,
                     KNOWLEDGE_SNAPSHOT_LABEL);
             DataInputStream input = new DataInputStream(new BufferedInputStream(boundedInput))) {
            return readKnowledgeSnapshotBody(
                    new SnapshotInput(input, SnapshotStringEncoding.UTF16_CHARS), FORMAT_VERSION_V2);
        } catch (EOFException exception) {
            throw new IOException("truncated knowledge snapshot", exception);
        }
    }

    static CodeKnowledgeSnapshot readKnowledgeSnapshotV2FromBytes(byte[] payload, long maxPersistedBytes)
            throws IOException {
        if (payload.length > maxPersistedBytes) {
            throw new IOException("knowledge snapshot payload exceeds persisted byte limit: " + payload.length);
        }
        try (BoundedInputStream boundedInput = new BoundedInputStream(
                     new ByteArrayInputStream(payload),
                     maxPersistedBytes,
                     KNOWLEDGE_SNAPSHOT_LABEL);
             DataInputStream input = new DataInputStream(new BufferedInputStream(boundedInput))) {
            return readKnowledgeSnapshotBody(
                    new SnapshotInput(input, SnapshotStringEncoding.UTF16_CHARS), FORMAT_VERSION_V2);
        } catch (EOFException exception) {
            throw new IOException("truncated knowledge snapshot", exception);
        }
    }

    private static CodeKnowledgeSnapshot readKnowledgeSnapshotBody(SnapshotInput input, int version) throws IOException {
        requireHeader(input, version, KNOWLEDGE_SNAPSHOT_LABEL);
        UUID projectId = new UUID(input.readLong(), input.readLong());
        String snapshotId = input.readRequiredString("snapshotId");
        int symbolCount = input.readCount(MAX_SYMBOLS, "symbol count");
        List<Symbol> symbols = new ArrayList<>(initialCapacity(symbolCount));
        for (int index = 0; index < symbolCount; index++) {
            symbols.add(SnapshotEntityCodec.readSymbol(input));
        }
        int occurrenceCount = input.readCount(MAX_OCCURRENCES, "occurrence count");
        List<SymbolOccurrence> occurrences = new ArrayList<>(initialCapacity(occurrenceCount));
        for (int index = 0; index < occurrenceCount; index++) {
            occurrences.add(SnapshotEntityCodec.readOccurrence(input));
        }
        int relationshipCount = input.readCount(MAX_RELATIONSHIPS, "relationship count");
        List<Relationship> relationships = new ArrayList<>(initialCapacity(relationshipCount));
        for (int index = 0; index < relationshipCount; index++) {
            relationships.add(SnapshotEntityCodec.readRelationship(input));
        }
        if (input.read() != -1) {
            throw new IOException("unexpected trailing data in knowledge snapshot");
        }
        try {
            return new CodeKnowledgeSnapshot(
                    projectId,
                    snapshotId,
                    symbols,
                    occurrences,
                    relationships
            );
        } catch (IllegalArgumentException exception) {
            throw new IOException("invalid knowledge snapshot: " + exception.getMessage(), exception);
        }
    }

    private static void requireSnapshotFileSize(Path file, long maxPersistedBytes) throws IOException {
        long size = Files.size(file);
        if (size < 1L || size > maxPersistedBytes) {
            throw new IOException("snapshot payload exceeds persisted byte limit: " + size
                    + "/" + maxPersistedBytes);
        }
    }

    private static void requireHeader(SnapshotInput input, int expectedVersion, String name) throws IOException {
        int version = SnapshotBinaryPrimitives.readHeaderVersion(input.stream(), SNAPSHOT_MAGIC, name);
        if (version != expectedVersion) {
            throw new IOException("unsupported " + name + " version: " + version);
        }
    }
}
