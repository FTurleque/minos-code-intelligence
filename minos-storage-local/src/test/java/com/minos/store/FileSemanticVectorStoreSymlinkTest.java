package com.minos.store;

import com.minos.semantic.SemanticDocument;
import com.minos.semantic.SemanticDocumentKind;
import com.minos.semantic.SemanticVector;
import com.minos.semantic.SemanticVectorStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSemanticVectorStoreSymlinkTest {

    @Test
    void rejectsSymlinkedStoreRoot(@TempDir Path temp) throws Exception {
        Path outside = Files.createDirectories(temp.resolve("outside"));
        Path root = temp.resolve("semantic");
        if (!createSymbolicLink(root, outside)) return;

        assertThrows(IOException.class, () -> new FileSemanticVectorStore(root));
    }

    @Test
    void rejectsSymlinkedProjectDirectory(@TempDir Path temp) throws Exception {
        Path root = temp.resolve("semantic");
        FileSemanticVectorStore store = new FileSemanticVectorStore(root);
        Path outside = Files.createDirectories(temp.resolve("outside"));
        if (!createSymbolicLink(root.resolve("project-1"), outside)) return;

        assertThrows(IOException.class, () -> store.load("project-1"));
    }

    @Test
    void cachedLoadStillRejectsIndexLeafRetargetedThroughSymlink(@TempDir Path temp) throws Exception {
        Path root = temp.resolve("semantic");
        FileSemanticVectorStore store = new FileSemanticVectorStore(root);
        store.replace(index());
        store.load("project-1").orElseThrow();

        Path index = root.resolve("project-1/index-v2.bin");
        Path outside = temp.resolve("outside-index.bin");
        Files.move(index, outside, StandardCopyOption.REPLACE_EXISTING);
        if (!createSymbolicLink(index, outside)) return;

        IOException rejected = assertThrows(IOException.class, () -> store.load("project-1"));
        assertTrue(rejected.getMessage().contains("symbolic link"));
    }

    @Test
    void rejectsSymlinkedSemanticSyncLockDirectory(@TempDir Path temp) throws Exception {
        Path root = temp.resolve("semantic");
        FileSemanticVectorStore store = new FileSemanticVectorStore(root);
        Path outside = Files.createDirectories(temp.resolve("outside"));
        if (!createSymbolicLink(root.resolve(".sync-locks"), outside)) return;

        assertThrows(IOException.class, () -> store.replaceConditionally(
                index(), "snapshot-1", () -> Optional.of("snapshot-1")));
    }

    @Test
    void rejectsSymlinkedSemanticSyncLockLeaf(@TempDir Path temp) throws Exception {
        Path root = temp.resolve("semantic");
        FileSemanticVectorStore store = new FileSemanticVectorStore(root);
        Path locks = Files.createDirectories(root.resolve(".sync-locks"));
        Path outside = Files.writeString(temp.resolve("outside.lock"), "outside");
        if (!createSymbolicLink(locks.resolve("project-1.lock"), outside)) return;

        assertThrows(IOException.class, () -> store.replaceConditionally(
                index(), "snapshot-1", () -> Optional.of("snapshot-1")));
    }

    private static SemanticVectorStore.IndexSnapshot index() {
        String stableKey = "symbol:fixture";
        SemanticDocument document = new SemanticDocument(
                "semantic:snapshot-1:fixture",
                stableKey,
                "project-1",
                "snapshot-1",
                SemanticDocumentKind.SYMBOL,
                "fixture",
                "src/Fixture.java",
                1,
                2,
                "content fixture",
                "checksum-fixture");
        return new SemanticVectorStore.IndexSnapshot(
                "project-1",
                "snapshot-1",
                "provider",
                "model-v1",
                2,
                1234L,
                List.of(new SemanticVectorStore.IndexedDocument(
                        document, new SemanticVector(stableKey, List.of(1.0, 0.0)))));
    }

    private static boolean createSymbolicLink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (UnsupportedOperationException | IOException exception) {
            return false;
        }
    }
}
