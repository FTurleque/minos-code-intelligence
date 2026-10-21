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

    /**
     * AUD-PERF-09 : un snapshot écrit avant le plafond des preuves de tests liés, dont une relation porte plus
     * d'emplacements que le plafond, reste lisible et rend toutes ses preuves (le codec ne borne rien).
     */
    @Test
    void readsBackEveryEvidenceOfARelatedTestBeyondTheDerivationCap() throws Exception {
        UUID projectId = UUID.randomUUID();
        CodeKnowledgeSnapshot base = CodecFixtures.everyBranch(projectId, "related-test-uncapped");
        com.minos.domain.CodeEntityRef test = new com.minos.domain.CodeEntityRef(
                com.minos.domain.CodeEntityType.SYMBOL, "method-string");
        com.minos.domain.CodeEntityRef production = new com.minos.domain.CodeEntityRef(
                com.minos.domain.CodeEntityType.SYMBOL, "method-int");
        List<com.minos.domain.Evidence> evidence = new java.util.ArrayList<>();
        for (int line = 10; line < 10 + 12; line++) {
            evidence.add(new com.minos.domain.Evidence(com.minos.domain.EvidenceType.DIRECT_REFERENCE,
                    "Resolved reference from test file to Converter", test, production,
                    CodecFixtures.location("file-caller", line), 0.65));
        }
        List<com.minos.domain.Relationship> relationships = new java.util.ArrayList<>(base.relationships());
        relationships.add(new com.minos.domain.Relationship("rel-related-test", projectId.toString(), test, production,
                null, com.minos.domain.RelationshipKind.RELATED_TEST, null, com.minos.domain.ResolutionStatus.RESOLVED,
                com.minos.domain.InformationNature.DERIVED, 0.65, CodecFixtures.origin(), evidence));
        CodeKnowledgeSnapshot snapshot = new CodeKnowledgeSnapshot(projectId, base.snapshotId(), base.symbols(),
                base.occurrences(), relationships);
        Path file = temporary.resolve("related-test.knowledge");

        KnowledgeSnapshotCodecs.select(snapshot).codec().write(file, snapshot);
        CodeKnowledgeSnapshot read = KnowledgeSnapshotCodecs.read(file);

        assertEquals(snapshot, read);
        assertEquals(12, read.relationships().stream()
                .filter(relationship -> relationship.id().equals("rel-related-test"))
                .findFirst().orElseThrow().evidence().size());
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
    void aNonAsciiStringOverEightMebibytesButWithinTheCharacterLimitRoundTripsExactly() throws Exception {
        // 8 Mi caractères « é » : 16 Mio en UTF-8, au-dessus de 8 Mio mais sous la borne de 3 × 8 Mi octets.
        String wide = "é".repeat(8 * 1024 * 1024);
        UUID project = UUID.randomUUID();
        CodeKnowledgeSnapshot snapshot = new CodeKnowledgeSnapshot(project, "wide",
                List.of(PersistedSizeFixtures.symbol(project, 0, wide)), List.of(), List.of());
        Path file = temporary.resolve("wide.knowledge");

        new SnapshotCodecV3().write(file, snapshot);

        assertTrue(Files.size(file) > 16L * 1024 * 1024, "the string alone takes 16 MiB in UTF-8");
        assertEquals(snapshot, new SnapshotCodecV3().read(file));
        assertEquals(new SnapshotCodecV3().encodedSize(snapshot), Files.size(file));
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
