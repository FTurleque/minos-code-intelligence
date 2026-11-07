package com.minos.storage.codec;

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

    private KnowledgeSnapshotCodecs() {
    }

    /**
     * Codec and exact encoded size for a new knowledge snapshot; refuses, before any I/O, a snapshot above
     * {@link SnapshotCodec#MAX_PERSISTED_SNAPSHOT_BYTES}.
     */
    public static Selection select(CodeKnowledgeSnapshot snapshot) throws IOException {
        return select(snapshot, SnapshotLimits.production());
    }

    /** As {@link #select(CodeKnowledgeSnapshot)}, refusing a snapshot above {@code limits.maxPersistedBytes()}. */
    public static Selection select(CodeKnowledgeSnapshot snapshot, SnapshotLimits limits) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(limits, "limits");
        long ceiling = limits.maxPersistedBytes();
        SnapshotCodecV3 v3 = new SnapshotCodecV3(limits);
        OptionalLong utf8 = v3.encodedSizeIfEncodable(snapshot);
        if (utf8.isPresent()) {
            return new Selection(v3, SnapshotBinaryCodecSupport.requirePersistable(utf8.getAsLong(), ceiling));
        }
        SnapshotCodec v2 = forVersion(SnapshotBinaryCodecSupport.FORMAT_VERSION_V2, limits);
        return new Selection(v2, v2.requirePersistable(snapshot));
    }

    /** Codec of a known format version. */
    public static SnapshotCodec forVersion(int formatVersion) throws IOException {
        return forVersion(formatVersion, SnapshotLimits.production());
    }

    /** Codec of a known format version, bound to the ceiling of {@code limits}. */
    public static SnapshotCodec forVersion(int formatVersion, SnapshotLimits limits) throws IOException {
        Objects.requireNonNull(limits, "limits");
        return switch (formatVersion) {
            case SnapshotBinaryCodecSupport.FORMAT_VERSION_V1 -> new SnapshotCodecV1(limits);
            case SnapshotBinaryCodecSupport.FORMAT_VERSION_V2 -> new SnapshotCodecV2(limits);
            case SnapshotBinaryCodecSupport.FORMAT_VERSION_V3 -> new SnapshotCodecV3(limits);
            default -> throw new IOException("unsupported knowledge snapshot format version: " + formatVersion);
        };
    }

    /** Format version declared by a stored payload's header. */
    public static int formatVersionOf(Path payload) throws IOException {
        return formatVersionOf(payload, SnapshotLimits.production());
    }

    /** Format version declared by a stored payload's header, refusing a payload above the ceiling of {@code limits}. */
    public static int formatVersionOf(Path payload, SnapshotLimits limits) throws IOException {
        return SnapshotBinaryCodecSupport.readFormatVersion(
                Objects.requireNonNull(payload, "payload"), Objects.requireNonNull(limits, "limits").maxPersistedBytes());
    }

    /** Reads a stored payload in the format its header declares. */
    public static CodeKnowledgeSnapshot read(Path payload) throws IOException {
        return read(payload, SnapshotLimits.production());
    }

    /** Reads a stored payload in the format its header declares, up to the ceiling of {@code limits}. */
    public static CodeKnowledgeSnapshot read(Path payload, SnapshotLimits limits) throws IOException {
        return forVersion(formatVersionOf(payload, limits), limits).read(payload);
    }

    public record Selection(SnapshotCodec codec, long encodedBytes) {
        public Selection {
            Objects.requireNonNull(codec, "codec");
        }
    }
}
