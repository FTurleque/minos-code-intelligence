package com.minos.storage.local.store;

import com.minos.domain.Symbol;
import com.minos.domain.SymbolOccurrence;
import com.minos.store.CodeKnowledgeSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * A decoded snapshot shares one instance per distinct string (ADR 0047, option A): the project id, the
 * origin strings and the file ids are repeated on every entity, and decoding them as separate instances
 * made a reread snapshot retain about 2.5 times the heap of the ingested one.
 */
class SnapshotDecodeStringSharingTest {

    @Test
    void utf8DecodingSharesEqualStrings(@TempDir Path temporary) throws Exception {
        assertEqualStringsAreShared(new SnapshotCodecV3(), temporary.resolve("shared.knowledge"));
    }

    @Test
    void utf16DecodingSharesEqualStrings(@TempDir Path temporary) throws Exception {
        assertEqualStringsAreShared(new SnapshotCodecV2(), temporary.resolve("shared.v2"));
    }

    @Test
    void sharingIsLocalToOneDecodingPass(@TempDir Path temporary) throws Exception {
        UUID projectId = UUID.randomUUID();
        SnapshotCodecV3 codec = new SnapshotCodecV3();
        Path file = temporary.resolve("twice.knowledge");
        codec.write(file, QueryViewWeightFixtures.repository(projectId, "twice", 2,
                List.<String[]>of(QueryViewWeightFixtures.ASCII)));

        CodeKnowledgeSnapshot first = codec.read(file);
        CodeKnowledgeSnapshot second = codec.read(file);

        assertEquals(first, second);
        // No table survives a read: nothing is retained between two decoding passes.
        assertNotSame(first.symbols().getFirst().projectId(), second.symbols().getFirst().projectId());
    }

    private static void assertEqualStringsAreShared(SnapshotCodec codec, Path file) throws Exception {
        UUID projectId = UUID.randomUUID();
        CodeKnowledgeSnapshot written = QueryViewWeightFixtures.repository(projectId, "shared", 2,
                List.of(QueryViewWeightFixtures.ASCII, QueryViewWeightFixtures.LATIN1, QueryViewWeightFixtures.CJK));
        codec.write(file, written);

        CodeKnowledgeSnapshot decoded = codec.read(file);

        assertEquals(written, decoded);
        Symbol first = decoded.symbols().get(0);
        Symbol second = decoded.symbols().get(1);
        SymbolOccurrence occurrence = decoded.occurrences().getFirst();
        assertSame(first.projectId(), second.projectId(), "project id of two symbols");
        assertSame(first.projectId(), occurrence.projectId(), "project id of a symbol and an occurrence");
        assertSame(first.projectId(), decoded.relationships().getFirst().projectId(), "project id of a relationship");
        assertSame(first.fileId(), first.location().fileId(), "file id of a symbol and of its location");
        assertSame(first.fileId(), second.fileId(), "file id of two symbols of one file");
        assertSame(first.language(), second.language(), "language");
        assertSame(first.origin().providerId(), occurrence.origin().providerId(), "origin provider id");
    }
}
