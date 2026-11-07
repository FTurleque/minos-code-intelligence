package com.minos.storage.local.store;

import com.minos.storage.codec.PersistedSizeFixtures;
import com.minos.storage.codec.SnapshotCodec;
import com.minos.storage.codec.SnapshotCodecV2;
import com.minos.storage.codec.SnapshotCodecV3;
import com.minos.storage.codec.SnapshotLimits;
import com.minos.diagnostics.PublicErrorMessages;
import com.minos.domain.Symbol;
import com.minos.store.CodeKnowledgeSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A snapshot above the persisted ceiling is refused before any byte is written (A6, step 1): no
 * temporary file, no partial file, no pointer change, and a message that is safe to surface. The ceiling of
 * the store under test is lowered, so no snapshot of the production size is built.
 */
class PersistedSnapshotLimitTest {

    static final long CEILING = 24L * 1024L * 1024L;
    static final SnapshotLimits LIMITS = SnapshotLimits.production().withMaxPersistedBytes(CEILING);
    static final String REFUSAL_PREFIX = "knowledge snapshot is too large to persist: ";

    @Test
    void oversizedSnapshotLeavesNoFileAndKeepsTheActiveSnapshot(@TempDir Path root) throws Exception {
        UUID projectId = UUID.randomUUID();
        FileSymbolSnapshotStore store = new FileSymbolSnapshotStore(root, LIMITS);
        store.publish(projectId, "small", FileSymbolSnapshotStoreTest.symbols(projectId), List.of(), List.of());
        List<Path> before = listFiles(root);
        CodeKnowledgeSnapshot oversized = PersistedSizeFixtures.largerThan(projectId, "oversized", CEILING);

        IOException refused = assertThrows(IOException.class, () -> store.publish(projectId, "oversized",
                oversized.symbols(), oversized.occurrences(), oversized.relationships()));

        assertRefusalMessage(refused.getMessage(), CEILING);
        assertEquals(before, listFiles(root), "no temporary, partial or pointer file may change");
        assertEquals("small", store.loadActiveKnowledge(projectId).orElseThrow().snapshotId());
    }

    @Test
    void oversizedSnapshotIsRefusedBeforeTheProjectDirectoryIsTouched(@TempDir Path root) throws Exception {
        UUID projectId = UUID.randomUUID();
        FileSymbolSnapshotStore store = new FileSymbolSnapshotStore(root, LIMITS);
        // Any file-system write for this project would first need its directory; a regular file in its
        // place makes every such attempt fail differently. Only a refusal decided before I/O passes.
        Path blocker = Files.writeString(store.storageRoot().resolve(projectId.toString()), "not a directory");
        CodeKnowledgeSnapshot oversized = PersistedSizeFixtures.largerThan(projectId, "oversized", CEILING);

        IOException refused = assertThrows(IOException.class, () -> store.publish(projectId, "oversized",
                oversized.symbols(), oversized.occurrences(), oversized.relationships()));

        assertRefusalMessage(refused.getMessage(), CEILING);
        assertEquals("not a directory", Files.readString(blocker));
    }

    @Test
    void snapshotAtTheCeilingIsPersistedAndOneMoreByteIsRefused(@TempDir Path root) throws Exception {
        UUID projectId = UUID.randomUUID();
        FileSymbolSnapshotStore store = new FileSymbolSnapshotStore(root, LIMITS);
        // ASCII content: the store writes it in V3 (ADR 0046), so the ceiling is measured in V3.
        SnapshotCodec codec = new SnapshotCodecV3();
        long ceiling = LIMITS.maxPersistedBytes();
        CodeKnowledgeSnapshot overByOne = PersistedSizeFixtures.ofEncodedSize(projectId, "over-by-one", ceiling + 1L, codec);
        CodeKnowledgeSnapshot atCeiling = PersistedSizeFixtures.ofEncodedSize(projectId, "at-ceiling", ceiling, codec);
        assertEquals(ceiling + 1L, codec.encodedSize(overByOne));
        assertEquals(ceiling, codec.encodedSize(atCeiling));

        IOException refused = assertThrows(IOException.class, () -> store.publish(projectId, "over-by-one",
                overByOne.symbols(), overByOne.occurrences(), overByOne.relationships()));
        assertRefusalMessage(refused.getMessage(), ceiling);
        assertTrue(refused.getMessage().contains(": " + (ceiling + 1L) + " encoded bytes"), refused.getMessage());

        store.publish(projectId, "at-ceiling", atCeiling.symbols(), atCeiling.occurrences(), atCeiling.relationships());
        List<Path> published = listFiles(store.storageRoot().resolve(projectId.toString())).stream()
                .filter(path -> path.toString().endsWith(".knowledge")).toList();
        assertEquals(1, published.size());
        assertEquals(ceiling, Files.size(store.storageRoot().resolve(projectId.toString()).resolve(published.getFirst())));
    }

    @Test
    void oversizedSnapshotKeptInV2ForAnUnpairedSurrogateIsRefusedOnItsV2Size(@TempDir Path root) throws Exception {
        UUID projectId = UUID.randomUUID();
        FileSymbolSnapshotStore store = new FileSymbolSnapshotStore(root, LIMITS);
        // About 17 MB in V3 but 34 MB in V2; the unpaired surrogate forces V2, hence the refusal.
        List<Symbol> symbols = new ArrayList<>(
                PersistedSizeFixtures.withTail(projectId, "v2-only", 2, 1_000_000).symbols());
        symbols.add(PersistedSizeFixtures.symbol(projectId, 99, "orphelin" + (char) 0xD800));
        CodeKnowledgeSnapshot snapshot = new CodeKnowledgeSnapshot(projectId, "v2-only", symbols, List.of(), List.of());
        long v2Size = new SnapshotCodecV2().encodedSize(snapshot);
        assertTrue(v2Size > CEILING, "the V2 form must be above the lowered ceiling: " + v2Size);

        IOException refused = assertThrows(IOException.class, () -> store.publish(projectId, "v2-only",
                snapshot.symbols(), snapshot.occurrences(), snapshot.relationships()));

        assertRefusalMessage(refused.getMessage(), CEILING);
        assertTrue(refused.getMessage().contains(": " + v2Size + " encoded bytes"), refused.getMessage());
        assertTrue(new SnapshotCodecV3().encodedSizeIfEncodable(snapshot).isEmpty());
    }

    @Test
    void theProductionCeilingIsFourHundredMebibytesAndTheRefusalNamesIt() {
        assertEquals(400L * 1024L * 1024L, SnapshotLimits.production().maxPersistedBytes());
        assertEquals(SnapshotLimits.production().maxPersistedBytes(), SnapshotCodec.MAX_PERSISTED_SNAPSHOT_BYTES);
        assertEquals(" encoded bytes exceed the 419430400-byte limit (400 MiB); nothing was written",
                refusalLimit(SnapshotLimits.production().maxPersistedBytes()));
    }

    static String refusalLimit(long ceiling) {
        return " encoded bytes exceed the " + ceiling + "-byte limit (" + ceiling / (1024L * 1024L)
                + " MiB); nothing was written";
    }

    static void assertRefusalMessage(String message, long ceiling) {
        assertTrue(message != null && message.startsWith(REFUSAL_PREFIX) && message.endsWith(refusalLimit(ceiling)),
                "unexpected refusal message: " + message);
        assertEquals(message, PublicErrorMessages.sanitize(message, "fallback"),
                "the refusal must survive public-message sanitization unchanged");
    }

    private static List<Path> listFiles(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile).map(root::relativize).sorted().toList();
        }
    }
}
