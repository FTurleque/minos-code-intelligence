package com.minos.store;

import com.minos.semantic.SemanticDocument;
import com.minos.semantic.SemanticDocumentKind;
import com.minos.semantic.SemanticVector;
import com.minos.semantic.SemanticVectorStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSemanticVectorStoreTest {

    private static final int MAGIC = 0x4D53454D;

    @Test
    void roundTripsCompactV2CachesAndAtomicallyReplacesReconstructibleIndex(@TempDir Path temp) throws Exception {
        FileSemanticVectorStore store = new FileSemanticVectorStore(temp.resolve("semantic"));
        SemanticVectorStore.IndexSnapshot first = index("project-1", "snapshot-1", "model-v1", "alpha", List.of(1.0, 0.0));

        store.replace(first);
        assertEquals(2, store.formatVersion("project-1"));
        SemanticVectorStore.IndexSnapshot loaded = store.load("project-1").orElseThrow();
        assertEquals("snapshot-1", loaded.snapshotId());
        assertEquals("provider", loaded.providerId());
        assertEquals("model-v1", loaded.modelId());
        assertEquals(2, loaded.dimensions());
        assertEquals("symbol:alpha", loaded.documents().getFirst().document().stableKey());
        assertEquals(List.of(1.0, 0.0), loaded.documents().getFirst().vector().values());
        assertEquals(1.0, loaded.documents().getFirst().vector().valueAt(0));
        assertEquals(1.0, loaded.documents().getFirst().vector().norm());
        assertSame(loaded, store.load("project-1").orElseThrow());
        assertTrue(store.sizeBytes("project-1") > 0);

        FileSemanticVectorStore reopened = new FileSemanticVectorStore(store.root());
        SemanticVectorStore.IndexSnapshot reconstructed = reopened.load("project-1").orElseThrow();
        assertEquals(loaded.snapshotId(), reconstructed.snapshotId());
        assertEquals(loaded.documents(), reconstructed.documents());

        SemanticVectorStore.IndexSnapshot second = index("project-1", "snapshot-2", "model-v1", "beta", List.of(0.0, 1.0));
        store.replace(second);
        SemanticVectorStore.IndexSnapshot replaced = store.load("project-1").orElseThrow();
        assertEquals("snapshot-2", replaced.snapshotId());
        assertEquals("symbol:beta", replaced.documents().getFirst().document().stableKey());

        store.delete("project-1");
        assertEquals(0, store.formatVersion("project-1"));
        assertFalse(store.load("project-1").isPresent());
    }

    @Test
    void readsLegacyV1AndMigratesOnNextReplace(@TempDir Path temp) throws Exception {
        Path root = temp.resolve("semantic");
        Files.createDirectories(root.resolve("project-1"));
        SemanticVectorStore.IndexSnapshot legacy = index(
                "project-1", "snapshot-legacy", "model-v1", "legacy", List.of(0.125, -0.5));
        writeLegacyV1(root.resolve("project-1/index-v1.bin"), legacy);

        FileSemanticVectorStore store = new FileSemanticVectorStore(root);
        assertEquals(1, store.formatVersion("project-1"));
        SemanticVectorStore.IndexSnapshot loaded = store.load("project-1").orElseThrow();
        assertEquals("snapshot-legacy", loaded.snapshotId());
        assertEquals(0.125, loaded.documents().getFirst().vector().valueAt(0));

        store.replace(loaded);
        assertEquals(2, store.formatVersion("project-1"));
        assertFalse(Files.exists(root.resolve("project-1/index-v1.bin")));
        assertTrue(Files.isRegularFile(root.resolve("project-1/index-v2.bin")));
    }

    private static void writeLegacyV1(Path file, SemanticVectorStore.IndexSnapshot snapshot) throws Exception {
        SemanticVectorStore.IndexedDocument indexed = snapshot.documents().getFirst();
        SemanticDocument document = indexed.document();
        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))) {
            output.writeInt(MAGIC);
            output.writeInt(1);
            writeString(output, snapshot.projectId());
            writeString(output, snapshot.snapshotId());
            writeString(output, snapshot.providerId());
            writeString(output, snapshot.modelId());
            output.writeInt(snapshot.dimensions());
            output.writeLong(snapshot.builtAtEpochMilli());
            output.writeInt(1);
            writeString(output, document.id());
            writeString(output, document.stableKey());
            writeString(output, document.kind().name());
            writeString(output, document.sourceId());
            writeString(output, document.fileId() == null ? "" : document.fileId());
            output.writeInt(document.startLine());
            output.writeInt(document.endLine());
            writeString(output, document.content());
            writeString(output, document.checksum());
            output.writeInt(indexed.vector().dimensions());
            for (double value : indexed.vector().values()) output.writeDouble(value);
        }
    }

    private static void writeString(DataOutputStream output, String value) throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static SemanticVectorStore.IndexSnapshot index(
            String projectId,
            String snapshotId,
            String modelId,
            String suffix,
            List<Double> vector
    ) {
        String stableKey = "symbol:" + suffix;
        SemanticDocument document = new SemanticDocument(
                "semantic:" + snapshotId + ":" + suffix,
                stableKey,
                projectId,
                snapshotId,
                SemanticDocumentKind.SYMBOL,
                suffix,
                "src/Fixture.java",
                1,
                2,
                "content " + suffix,
                "checksum-" + suffix);
        return new SemanticVectorStore.IndexSnapshot(
                projectId,
                snapshotId,
                "provider",
                modelId,
                vector.size(),
                1234L,
                List.of(new SemanticVectorStore.IndexedDocument(document, new SemanticVector(stableKey, vector))));
    }
}
