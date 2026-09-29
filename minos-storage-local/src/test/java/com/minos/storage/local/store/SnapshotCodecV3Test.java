package com.minos.storage.local.store;

import com.minos.store.CodeKnowledgeSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Format V3 (ADR 0046) : chaînes UTF-8 exactes, décodage strict et borné en octets. */
class SnapshotCodecV3Test {

    @TempDir
    Path temporary;

    @Test
    void roundTripsEveryTrapStringExactly() throws Exception {
        CodeKnowledgeSnapshot snapshot = LegacySnapshotContent.v2();
        SnapshotCodecV3 codec = new SnapshotCodecV3();
        Path file = temporary.resolve("traps.knowledge");

        SnapshotCodec.SnapshotEncoding encoding = codec.write(file, snapshot);

        assertEquals(snapshot, codec.read(file));
        assertEquals(new SnapshotIntegrityService().checksum(file), encoding.sha256());
        assertEquals(3, KnowledgeSnapshotCodecs.formatVersionOf(file));
        assertEquals(snapshot, KnowledgeSnapshotCodecs.read(file));
        assertEquals(3, codec.formatVersion());
        assertEquals(".knowledge", codec.fileExtension());
    }

    @Test
    void asciiContentTakesAboutHalfTheV2Size() throws Exception {
        CodeKnowledgeSnapshot ascii = PersistedSizeFixtures.withTail(UUID.randomUUID(), "ascii", 1, 1_000);

        long v2 = new SnapshotCodecV2().encodedSize(ascii);
        long v3 = new SnapshotCodecV3().encodedSize(ascii);

        assertTrue(v3 * 2 < v2 + 1_000, "V3 " + v3 + " against V2 " + v2);
    }

    @Test
    void anUnpairedSurrogateHasNoV3FormAndIsNeverReplaced() throws Exception {
        CodeKnowledgeSnapshot orphan = LegacySnapshotContent.v2WithLoneSurrogate();
        SnapshotCodecV3 codec = new SnapshotCodecV3();

        assertTrue(codec.encodedSizeIfEncodable(orphan).isEmpty());
        assertThrows(IOException.class, () -> codec.encodedSize(orphan));
        assertThrows(IOException.class, () -> codec.write(temporary.resolve("orphan.knowledge"), orphan));
        KnowledgeSnapshotCodecs.Selection selection = KnowledgeSnapshotCodecs.select(orphan);
        assertEquals(2, selection.codec().formatVersion());
        assertEquals(selection.codec().encodedSize(orphan), selection.encodedBytes());
        assertEquals(3, KnowledgeSnapshotCodecs.select(LegacySnapshotContent.v2()).codec().formatVersion());
    }

    @Test
    void malformedUtf8IsReportedNotReplaced() throws Exception {
        // 0xED 0xA0 0x80 est un surrogate encodé (CESU-8), 0xC3 0x28 une suite invalide.
        for (byte[] malformed : List.of(new byte[] {(byte) 0xED, (byte) 0xA0, (byte) 0x80}, new byte[] {(byte) 0xC3, 0x28})) {
            Path file = header(temporary.resolve("malformed-" + malformed.length + ".knowledge"), malformed.length, malformed);

            IOException refused = assertThrows(IOException.class, () -> new SnapshotCodecV3().read(file));
            assertEquals("invalid UTF-8 string in knowledge snapshot", refused.getMessage());
        }
    }

    @Test
    void stringLengthsAreBoundedInBytesBeforeAnyRead() throws Exception {
        Path tooLong = header(temporary.resolve("too-long.knowledge"), 3 * 8 * 1024 * 1024 + 1, new byte[0]);
        assertEquals("invalid string length in knowledge snapshot: 25165825",
                assertThrows(IOException.class, () -> new SnapshotCodecV3().read(tooLong)).getMessage());

        // Longueur déclarée dans la borne mais au-delà du fichier : tronqué, sans allocation de 24 Mo.
        Path truncated = header(temporary.resolve("truncated.knowledge"), 3 * 8 * 1024 * 1024, "short".getBytes(StandardCharsets.US_ASCII));
        assertEquals("truncated knowledge snapshot",
                assertThrows(IOException.class, () -> new SnapshotCodecV3().read(truncated)).getMessage());
    }

    @Test
    void eachCodecRefusesTheOtherVersion() throws Exception {
        Path v3 = temporary.resolve("v3.knowledge");
        Path v2 = temporary.resolve("v2.knowledge");
        new SnapshotCodecV3().write(v3, LegacySnapshotContent.v2());
        new SnapshotCodecV2().write(v2, LegacySnapshotContent.v2());

        assertEquals("unsupported knowledge snapshot version: 3",
                assertThrows(IOException.class, () -> new SnapshotCodecV2().read(v3)).getMessage());
        assertEquals("unsupported knowledge snapshot version: 2",
                assertThrows(IOException.class, () -> new SnapshotCodecV3().read(v2)).getMessage());
        assertEquals(LegacySnapshotContent.v2(), KnowledgeSnapshotCodecs.read(v2));
        assertThrows(IOException.class, () -> KnowledgeSnapshotCodecs.forVersion(4));
    }

    /** En-tête V3 suivi d'un identifiant de snapshot de longueur déclarée et de contenu donnés, en octets. */
    private static Path header(Path file, int declaredLength, byte[] content) throws IOException {
        UUID project = UUID.randomUUID();
        try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(file))) {
            output.writeInt(0x4D4E5359);
            output.writeInt(3);
            output.writeLong(project.getMostSignificantBits());
            output.writeLong(project.getLeastSignificantBits());
            output.writeInt(declaredLength);
            output.write(content);
        }
        return file;
    }
}
