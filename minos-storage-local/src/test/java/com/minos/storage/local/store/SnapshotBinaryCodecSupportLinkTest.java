package com.minos.storage.local.store;

import com.minos.domain.Symbol;
import com.minos.io.PrivateLocalStorage;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.SymbolSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The binary snapshot writers open their file through the private, link-free stream (AUD-SEC-03): a link standing in
 * place of the file is refused and the file it points to is not written. The writers have no test of their own for
 * this: the stores create the file just before, and nothing else could put a link there between the two.
 */
class SnapshotBinaryCodecSupportLinkTest {

    private static Path linkTo(Path sentinel, Path link) throws IOException {
        try {
            return Files.createSymbolicLink(link, sentinel);
        } catch (UnsupportedOperationException | IOException unsupported) {
            assumeTrue(false, "this platform/account cannot create symbolic links");
            return link;
        }
    }

    @Test
    void everyWriterRefusesALinkAndLeavesItsTargetIntact(@TempDir Path temp) throws Exception {
        UUID projectId = UUID.randomUUID();
        CodeKnowledgeSnapshot knowledge = CodecFixtures.everyBranch(projectId, "snapshot-1");
        SymbolSnapshot symbols = new SymbolSnapshot(projectId, "snapshot-1", List.<Symbol>of());
        Path sentinel = Files.writeString(temp.resolve("sentinel.txt"), "untouched", StandardCharsets.UTF_8);

        Path v1 = linkTo(sentinel, temp.resolve("v1.bin"));
        assertThrows(IOException.class, () -> SnapshotBinaryCodecSupport.writeSymbolSnapshotV1(v1, symbols));
        Path v2 = linkTo(sentinel, temp.resolve("v2.bin"));
        assertThrows(IOException.class, () -> SnapshotBinaryCodecSupport.writeKnowledgeSnapshotV2(v2, knowledge));
        Path v3 = linkTo(sentinel, temp.resolve("v3.bin"));
        assertThrows(IOException.class, () -> SnapshotBinaryCodecSupport.writeKnowledgeSnapshotV3(v3, knowledge));

        assertEquals("untouched", Files.readString(sentinel));
    }

    @Test
    void aWriterHardensTheFileItIsGivenAndWritesIt(@TempDir Path temp) throws Exception {
        UUID projectId = UUID.randomUUID();
        // everyBranch holds an unpaired surrogate on purpose, which the codec refuses: a plain snapshot here.
        CodeKnowledgeSnapshot knowledge = new CodeKnowledgeSnapshot(
                projectId, "snapshot-1", List.of(), List.of(), List.of());
        Path file = Files.createFile(temp.resolve("v3.bin"));

        String sha = SnapshotBinaryCodecSupport.writeKnowledgeSnapshotV3(file, knowledge);

        assertEquals(64, sha.length());
        assertEquals(PrivateLocalStorage.Privacy.ENFORCED, PrivateLocalStorage.privacyOf(file));
        assertEquals(knowledge, SnapshotBinaryCodecSupport.readVerifiedKnowledgeSnapshotV3(file, sha));
    }
}
