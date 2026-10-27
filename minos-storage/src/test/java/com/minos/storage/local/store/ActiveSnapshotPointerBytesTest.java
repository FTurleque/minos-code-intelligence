package com.minos.storage.local.store;

import com.minos.storage.codec.LegacySnapshotContent;
import com.minos.store.SnapshotDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The active.pointer format is frozen: every pointer written by the published base reads back and is rewritten byte for
 * byte, whatever class carries the string, count and header primitives it is made of.
 */
class ActiveSnapshotPointerBytesTest {

    private static final String FIXTURES = "/snapshot-formats/base-d9ae1005/";
    private static final Map<String, UUID> POINTERS = Map.of(
            "v1-symbols", LegacySnapshotContent.V1_PROJECT,
            "v2-well-formed", LegacySnapshotContent.V2_PROJECT,
            "v2-lone-surrogate", LegacySnapshotContent.V2_LONE_SURROGATE_PROJECT);

    @TempDir
    Path home;

    @Test
    void everyHistoricalPointerIsRewrittenByteForByte() throws IOException {
        for (Map.Entry<String, UUID> fixture : POINTERS.entrySet()) {
            UUID project = fixture.getValue();
            byte[] original = pointerBytes(fixture.getKey(), project);
            Path source = home.resolve(fixture.getKey() + "-source");
            Path target = home.resolve(fixture.getKey() + "-target");
            ActiveSnapshotRepository reader = repository(source);
            Files.write(new SnapshotRepository(source).projectDirectory(project).resolve(reader.activeFileName()), original);

            SnapshotDescriptor pointer = reader.read(project).orElseThrow();
            ActiveSnapshotRepository writer = repository(target);
            writer.promote(project, pointer);

            byte[] rewritten = Files.readAllBytes(
                    new SnapshotRepository(target).projectDirectory(project).resolve(writer.activeFileName()));
            assertArrayEquals(original, rewritten, fixture.getKey());
        }
    }

    @Test
    void aPointerFollowedByAnExtraByteIsRefused() throws IOException {
        UUID project = LegacySnapshotContent.V2_PROJECT;
        byte[] original = pointerBytes("v2-well-formed", project);
        byte[] longer = new byte[original.length + 1];
        System.arraycopy(original, 0, longer, 0, original.length);
        ActiveSnapshotRepository reader = repository(home);
        Files.write(new SnapshotRepository(home).projectDirectory(project).resolve(reader.activeFileName()), longer);

        IOException refusal = assertThrows(IOException.class, () -> reader.read(project));

        assertEquals("unexpected trailing data in active snapshot pointer", refusal.getMessage());
    }

    private static ActiveSnapshotRepository repository(Path root) throws IOException {
        SnapshotRepository repository = new SnapshotRepository(root);
        for (UUID project : POINTERS.values()) {
            Files.createDirectories(repository.projectDirectory(project));
        }
        return new ActiveSnapshotRepository(repository);
    }

    private static byte[] pointerBytes(String fixture, UUID project) throws IOException {
        String resource = FIXTURES + fixture + "/" + project + "/active.pointer";
        try (InputStream input = ActiveSnapshotPointerBytesTest.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("missing fixture " + resource);
            }
            return input.readAllBytes();
        }
    }
}
