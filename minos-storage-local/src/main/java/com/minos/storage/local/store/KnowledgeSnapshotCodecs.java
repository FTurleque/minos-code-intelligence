package com.minos.storage.local.store;

import com.minos.store.CodeKnowledgeSnapshot;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Format policy shared by every knowledge-snapshot store (file and PostgreSQL), ADR 0046.
 *
 * <p>New snapshots are written in V3 (UTF-8 strings). A snapshot that holds an unpaired surrogate anywhere is
 * written whole in V2, which keeps it exactly: nothing is refused and nothing is silently replaced. Every
 * format ever written stays readable; the format of a stored payload is read from its own header.</p>
 */
public final class KnowledgeSnapshotCodecs {

    private static final SnapshotCodecV1 V1 = new SnapshotCodecV1();
    private static final SnapshotCodecV2 V2 = new SnapshotCodecV2();
    private static final SnapshotCodecV3 V3 = new SnapshotCodecV3();

    private KnowledgeSnapshotCodecs() {
    }

    /**
     * Codec and exact encoded size for a new knowledge snapshot; refuses, before any I/O, a snapshot above
     * {@link SnapshotCodec#MAX_PERSISTED_SNAPSHOT_BYTES}.
     */
    public static Selection select(CodeKnowledgeSnapshot snapshot) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        OptionalLong utf8 = V3.encodedSizeIfEncodable(snapshot);
        if (utf8.isPresent()) {
            return new Selection(V3, SnapshotBinaryCodecSupport.requirePersistable(utf8.getAsLong()));
        }
        return new Selection(V2, V2.requirePersistable(snapshot));
    }

    /** Codec of a known format version. */
    public static SnapshotCodec forVersion(int formatVersion) throws IOException {
        return switch (formatVersion) {
            case SnapshotBinaryCodecSupport.FORMAT_VERSION_V1 -> V1;
            case SnapshotBinaryCodecSupport.FORMAT_VERSION_V2 -> V2;
            case SnapshotBinaryCodecSupport.FORMAT_VERSION_V3 -> V3;
            default -> throw new IOException("unsupported knowledge snapshot format version: " + formatVersion);
        };
    }

    /** Format version declared by a stored payload's header. */
    public static int formatVersionOf(Path payload) throws IOException {
        return SnapshotBinaryCodecSupport.readFormatVersion(Objects.requireNonNull(payload, "payload"));
    }

    /** Reads a stored payload in the format its header declares. */
    public static CodeKnowledgeSnapshot read(Path payload) throws IOException {
        return forVersion(formatVersionOf(payload)).read(payload);
    }

    public record Selection(SnapshotCodec codec, long encodedBytes) {
        public Selection {
            Objects.requireNonNull(codec, "codec");
        }
    }
}
