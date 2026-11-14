package com.minos.storage.local.store;

import com.minos.semantic.SemanticDocumentKind;
import com.minos.semantic.SemanticVectorStore;
import com.minos.testsupport.SemanticDocumentReadsContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The local store reads the vector pages and the checksums of an index by walking its file: the whole index is
 * never loaded for them, whatever the order and the size of the pages.
 */
class FileSemanticVectorStoreStreamedReadsTest {

    private static final String PROJECT = "project-1";

    @TempDir
    Path temp;

    @Test
    void vectorPagesAndChecksumsAreReadWithoutLoadingTheIndex() throws Exception {
        FileSemanticVectorStore store = storeOf(250);

        List<String> walked = new ArrayList<>();
        String after = null;
        boolean more = true;
        while (more) {
            SemanticVectorStore.VectorPage page = store.vectorPage(PROJECT, after, 40).orElseThrow();
            page.vectors().forEach(vector -> walked.add(vector.stableKey()));
            more = page.hasMore();
            if (more) after = page.vectors().getLast().stableKey();
        }
        SemanticVectorStore.IndexChecksums checksums = store.checksums(PROJECT).orElseThrow();

        assertEquals(250, walked.size());
        assertEquals(walked.stream().sorted().toList(), walked);
        assertEquals(250, checksums.checksumsByStableKey().size());
        assertEquals("chk-doc-007", checksums.checksumsByStableKey().get("doc-007"));
        assertEquals(0L, store.cacheStats().misses(), "no page and no checksum reading loads the whole index");
        assertEquals(0, store.cacheStats().entries());
    }

    @Test
    void aPageAskedAfterAnyKeyIsTheSameWhetherTheWalkIsResumedOrStartedAgain() throws Exception {
        FileSemanticVectorStore walking = storeOf(90);
        FileSemanticVectorStore fresh = new FileSemanticVectorStore(walking.root());

        SemanticVectorStore.VectorPage first = walking.vectorPage(PROJECT, null, 30).orElseThrow();
        SemanticVectorStore.VectorPage resumed = walking.vectorPage(PROJECT, "doc-029", 30).orElseThrow();
        SemanticVectorStore.VectorPage restarted = fresh.vectorPage(PROJECT, "doc-029", 30).orElseThrow();
        SemanticVectorStore.VectorPage elsewhere = walking.vectorPage(PROJECT, "doc-010", 5).orElseThrow();
        SemanticVectorStore.VectorPage again = walking.vectorPage(PROJECT, "doc-029", 30).orElseThrow();

        assertEquals("doc-029", first.vectors().getLast().stableKey());
        assertEquals(restarted, resumed);
        assertEquals(restarted, again);
        assertEquals("doc-030", resumed.vectors().getFirst().stableKey());
        assertEquals(List.of("doc-011", "doc-012", "doc-013", "doc-014", "doc-015"),
                elsewhere.vectors().stream().map(SemanticVectorStore.StoredVector::stableKey).toList());
        assertEquals(SemanticDocumentKind.CHUNK, elsewhere.vectors().getFirst().kind());
    }

    @Test
    void aWalkResumedAfterTheIndexWasReplacedReadsTheNewIndexFromItsStart() throws Exception {
        FileSemanticVectorStore store = storeOf(60);
        store.vectorPage(PROJECT, null, 20).orElseThrow();

        store.replace(SemanticDocumentReadsContract.snapshot(PROJECT, "snap-2", 9L, documents("snap-2", 30)));
        SemanticVectorStore.VectorPage page = store.vectorPage(PROJECT, "doc-019", 20).orElseThrow();

        assertEquals("snap-2", page.metadata().snapshotId());
        assertEquals(30, page.metadata().documentCount());
        assertEquals(10, page.vectors().size());
        assertEquals("doc-020", page.vectors().getFirst().stableKey());
        assertFalse(page.hasMore());
    }

    @Test
    void aTruncatedIndexIsRefusedByBothReadingsWithoutNamingItsLocation() throws Exception {
        FileSemanticVectorStore store = storeOf(20);
        Path file = store.root().resolve(PROJECT).resolve("index-v2.bin");
        byte[] bytes = Files.readAllBytes(file);
        Files.write(file, Arrays.copyOf(bytes, bytes.length - 7));

        IOException page = assertThrows(IOException.class, () -> store.vectorPage(PROJECT, null, 50));
        IOException checksums = assertThrows(IOException.class, () -> store.checksums(PROJECT));

        assertEquals("truncated semantic index: index-v2.bin", page.getMessage());
        assertEquals("truncated semantic index: index-v2.bin", checksums.getMessage());
        assertFalse(page.getMessage().contains(temp.toString()));
    }

    @Test
    void trailingDataAndAForeignProjectAreRefusedByTheChecksumReading() throws Exception {
        FileSemanticVectorStore store = storeOf(3);
        Path file = store.root().resolve(PROJECT).resolve("index-v2.bin");
        byte[] bytes = Files.readAllBytes(file);
        byte[] longer = Arrays.copyOf(bytes, bytes.length + 1);
        Files.write(file, longer);
        IOException trailing = assertThrows(IOException.class, () -> store.checksums(PROJECT));

        Files.createDirectories(store.root().resolve("project-2"));
        Files.write(store.root().resolve("project-2").resolve("index-v2.bin"), bytes);
        IOException foreign = assertThrows(IOException.class, () -> store.vectorPage("project-2", null, 10));

        assertEquals("unexpected trailing semantic index data", trailing.getMessage());
        assertEquals("semantic index project mismatch", foreign.getMessage());
    }

    @Test
    void aWalkResumedAfterAKeyThatIsNotValidUtf8ReadsTheNextEntryWhereItStarts() throws Exception {
        FileSemanticVectorStore store = storeOf(60);
        Path file = store.root().resolve(PROJECT).resolve("index-v2.bin");
        byte[] bytes = Files.readAllBytes(file);
        byte[] key = {0, 0, 0, 7, 'd', 'o', 'c', '-', '0', '1', '9'};
        int at = indexOf(bytes, key);
        bytes[at + key.length - 1] = (byte) 0xFF;
        Files.write(file, bytes);

        SemanticVectorStore.VectorPage first = store.vectorPage(PROJECT, null, 20).orElseThrow();
        SemanticVectorStore.VectorPage resumed =
                store.vectorPage(PROJECT, first.vectors().getLast().stableKey(), 20).orElseThrow();

        assertEquals("doc-01\uFFFD", first.vectors().getLast().stableKey());
        assertEquals("doc-020", resumed.vectors().getFirst().stableKey());
        assertEquals(20, resumed.vectors().size());
        assertEquals(List.of(20.0, 1.0, -1.0), resumed.vectors().getFirst().vector().values());
    }

    private static int indexOf(byte[] bytes, byte[] pattern) {
        for (int at = 0; at <= bytes.length - pattern.length; at++) {
            if (Arrays.equals(bytes, at, at + pattern.length, pattern, 0, pattern.length)) return at;
        }
        throw new AssertionError("pattern not found");
    }

    @Test
    void anIndexInTheLegacyFormatIsStillReadByPagesAndItsChecksumsByWalkingTheFile() throws Exception {
        Path root = temp.resolve("legacy");
        Files.createDirectories(root.resolve(PROJECT));
        SemanticVectorStore.IndexSnapshot legacy = new SemanticVectorStore.IndexSnapshot(PROJECT, "snap-legacy",
                "test-provider", "test-model", SemanticDocumentReadsContract.DIMENSIONS, 3L,
                List.of(SemanticDocumentReadsContract.indexed(PROJECT, "snap-legacy", "doc-a", 0.25, -0.5, 1.0)));
        FileSemanticVectorStoreTest.writeLegacyV1(root.resolve(PROJECT).resolve("index-v1.bin"), legacy);
        FileSemanticVectorStore store = new FileSemanticVectorStore(root);

        SemanticVectorStore.VectorPage page = store.vectorPage(PROJECT, null, 10).orElseThrow();
        SemanticVectorStore.IndexChecksums checksums = store.checksums(PROJECT).orElseThrow();

        assertEquals(List.of(0.25, -0.5, 1.0), page.vectors().getFirst().vector().values());
        assertEquals("snap-legacy", page.metadata().snapshotId());
        assertEquals(java.util.Map.of("doc-a", "chk-doc-a"), checksums.checksumsByStableKey());
        assertTrue(page.metadata().equals(checksums.metadata()));
    }

    private FileSemanticVectorStore storeOf(int count) throws IOException {
        FileSemanticVectorStore store = new FileSemanticVectorStore(temp.resolve("semantic"));
        store.replace(SemanticDocumentReadsContract.snapshot(PROJECT, "snap-1", 5L, documents("snap-1", count)));
        return store;
    }

    private static List<SemanticVectorStore.IndexedDocument> documents(String snapshotId, int count) {
        List<SemanticVectorStore.IndexedDocument> documents = new ArrayList<>(count);
        for (int index = count - 1; index >= 0; index--) {
            documents.add(SemanticDocumentReadsContract.indexed(PROJECT, snapshotId,
                    String.format(Locale.ROOT, "doc-%03d", index), index, 1.0, -1.0));
        }
        return documents;
    }
}
