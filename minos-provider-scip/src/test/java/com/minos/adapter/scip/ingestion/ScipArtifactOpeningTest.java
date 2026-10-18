package com.minos.adapter.scip.ingestion;

import com.minos.orchestration.ScipSymbolSnapshotRequest;
import com.minos.storage.local.store.FileSymbolSnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.scip_code.scip.Index;
import org.scip_code.scip.Metadata;
import org.scip_code.scip.ToolInfo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * An artifact a provider wrote is opened through the confined channel and the frozen copy is written through the private
 * stream (AUD-SEC-03): a link put in place of either, after the path checks and before the open, is refused; the size
 * read on the open descriptor is the one which counts.
 */
class ScipArtifactOpeningTest {

    private static Path indexFile(Path directory) throws IOException {
        Path file = directory.resolve("index.scip");
        Index index = Index.newBuilder().setMetadata(Metadata.newBuilder()
                .setProjectRoot("file:///fixture")
                .setToolInfo(ToolInfo.newBuilder().setName("fixture-indexer").setVersion("1.0"))).build();
        try (var output = Files.newOutputStream(file)) {
            index.writeTo(output);
        }
        return file;
    }

    private static void linkInPlaceOf(Path file, Path sentinel, AtomicBoolean swapped) {
        try {
            Files.delete(file);
            Files.createSymbolicLink(file, sentinel);
            swapped.set(true);
        } catch (IOException | UnsupportedOperationException unsupported) {
            assumeTrue(false, "this platform/account cannot create symbolic links");
        }
    }

    // ----------------------------------------------------------------------------------- ScipIndexReader

    @Test
    void anIndexReplacedByALinkBetweenTheCheckAndTheOpenIsRefused(@TempDir Path temp) throws Exception {
        Path file = indexFile(temp);
        Path sentinel = Files.writeString(temp.resolve("sentinel.txt"), "untouched", StandardCharsets.UTF_8);
        AtomicBoolean swapped = new AtomicBoolean();
        ScipIndexReader reader = new ScipIndexReader(ScipIngestionLimits.DEFAULT, () -> linkInPlaceOf(file, sentinel, swapped));

        assertThrows(IOException.class, () -> reader.read(file));

        assertTrue(swapped.get());
        assertEquals("untouched", Files.readString(sentinel));
    }

    @Test
    void theSizeReadOnTheOpenDescriptorCounts(@TempDir Path temp) throws Exception {
        ScipIngestionLimits limits = new ScipIngestionLimits(64, 10, 10, 10, 10);
        Path file = Files.write(temp.resolve("index.scip"), new byte[32]);
        // The file grows beyond the limit after the path check, before the open.
        ScipIndexReader reader = new ScipIndexReader(limits, () -> {
            try {
                Files.write(file, new byte[200]);
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
        });

        IOException refusal = assertThrows(IOException.class, () -> reader.read(file));

        assertTrue(refusal.getMessage().contains("exceeds configured byte limit")
                        || refusal.getMessage().contains("outside configured byte limit"),
                refusal.getMessage());
    }

    @Test
    void anIndexWhichBecameEmptyAfterTheCheckIsRefused(@TempDir Path temp) throws Exception {
        Path file = indexFile(temp);
        ScipIndexReader reader = new ScipIndexReader(ScipIngestionLimits.DEFAULT, () -> {
            try {
                Files.write(file, new byte[0]);
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
        });

        assertThrows(IOException.class, () -> reader.read(file));
    }

    @Test
    void aDirectoryInPlaceOfTheIndexIsRefused(@TempDir Path temp) throws Exception {
        Path directory = Files.createDirectory(temp.resolve("index.scip"));

        assertThrows(IOException.class, () -> new ScipIndexReader().read(directory));
    }

    // ------------------------------------------------------------------------- ScipSymbolSnapshotImporter

    private static ScipSymbolSnapshotRequest request(UUID projectId) {
        return new ScipSymbolSnapshotRequest(projectId, "snapshot-1", null, "fixture-provider", "1", "fixture-run", Map.of());
    }

    @Test
    void aSourceReplacedByALinkBetweenTheCheckAndTheOpenIsRefusedAndNothingIsLeftFrozen(@TempDir Path temp)
            throws Exception {
        Path file = indexFile(temp);
        Path sentinel = Files.writeString(temp.resolve("sentinel.txt"), "untouched", StandardCharsets.UTF_8);
        Path scratch = temp.resolve("scratch");
        AtomicBoolean swapped = new AtomicBoolean();
        ScipSymbolSnapshotImporter importer = new ScipSymbolSnapshotImporter(
                ScipIngestionLimits.DEFAULT, scratch, () -> linkInPlaceOf(file, sentinel, swapped), created -> { });
        UUID projectId = UUID.randomUUID();
        FileSymbolSnapshotStore store = new FileSymbolSnapshotStore(temp.resolve("snapshots"));

        assertThrows(IOException.class, () -> importer.importSnapshot(file, request(projectId), store));

        assertTrue(swapped.get());
        assertEquals("untouched", Files.readString(sentinel));
        assertTrue(store.loadActiveKnowledge(projectId).isEmpty());
        assertEquals(0L, scratchFiles(scratch), "the frozen copy of a refused artifact is deleted");
    }

    @Test
    void aFrozenFileReplacedByALinkBeforeTheWriteIsRefusedAndTheTargetIsIntact(@TempDir Path temp) throws Exception {
        Path file = indexFile(temp);
        Path sentinel = Files.writeString(temp.resolve("sentinel.txt"), "untouched", StandardCharsets.UTF_8);
        Path scratch = temp.resolve("scratch");
        AtomicBoolean swapped = new AtomicBoolean();
        Consumer<Path> afterFrozenCreated = frozen -> linkInPlaceOf(frozen, sentinel, swapped);
        ScipSymbolSnapshotImporter importer = new ScipSymbolSnapshotImporter(
                ScipIngestionLimits.DEFAULT, scratch, () -> { }, afterFrozenCreated);
        UUID projectId = UUID.randomUUID();
        FileSymbolSnapshotStore store = new FileSymbolSnapshotStore(temp.resolve("snapshots"));

        assertThrows(IOException.class, () -> importer.importSnapshot(file, request(projectId), store));

        assertTrue(swapped.get());
        assertEquals("untouched", Files.readString(sentinel), "the link was not written through");
        assertTrue(store.loadActiveKnowledge(projectId).isEmpty());
        assertEquals(0L, scratchFiles(scratch), "no frozen file is left behind");
    }

    private static long scratchFiles(Path scratch) throws IOException {
        if (!Files.exists(scratch)) return 0L;
        try (Stream<Path> files = Files.list(scratch)) {
            return files.count();
        }
    }
}
