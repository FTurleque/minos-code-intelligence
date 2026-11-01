package com.minos.storage.home;

import com.minos.domain.registry.ProjectMount;
import com.minos.domain.registry.ProjectPathMapping;
import com.minos.io.PrivateLocalStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The versioned mapping file: format 1 (one root) is still read, format 2 (a mount table) is written and read. */
class ProjectPathMappingStoreTest {

    private static final String CONTAINER = "/workspace/projects";

    @TempDir
    Path home;

    private void write(String content) throws IOException {
        Path runtime = PrivateLocalStorage.ensurePrivateDirectory(home.resolve(ProjectPathMappingStore.RUNTIME_DIRECTORY));
        PrivateLocalStorage.writePrivateFile(runtime.resolve(ProjectPathMappingStore.FILE_NAME),
                content.getBytes(StandardCharsets.ISO_8859_1));
    }

    @Test
    void aMountTableIsWrittenAsFormatTwoAndReadBack() throws IOException {
        ProjectPathMappingStore store = new ProjectPathMappingStore(home);
        ProjectPathMapping table = ProjectPathMapping.mounted(CONTAINER, List.of(
                new ProjectMount("app", "D:/src/app"),
                new ProjectMount("team/b", "N:/workspace-dev/team/b")));

        store.save(table);

        String content = Files.readString(store.file(), StandardCharsets.ISO_8859_1);
        assertTrue(content.contains("formatVersion=2"), content);
        assertTrue(content.contains("mount.0.path=app"), content);
        assertTrue(content.contains("mount.1.hostRoot="), content);
        assertFalse(content.contains("hostRoot=N\\:/workspace-dev\n"), content);
        assertEquals(table, store.loadOptional().orElseThrow());
    }

    @Test
    void anEmptyTableIsReadBackEmpty() throws IOException {
        ProjectPathMappingStore store = new ProjectPathMappingStore(home);
        store.save(ProjectPathMapping.mounted(CONTAINER, List.of()));

        ProjectPathMapping loaded = store.loadOptional().orElseThrow();

        assertTrue(loaded.mountTable());
        assertTrue(loaded.mounts().isEmpty());
    }

    @Test
    void aSingleRootIsStillWrittenAndReadAsFormatOne() throws IOException {
        ProjectPathMappingStore store = new ProjectPathMappingStore(home);
        ProjectPathMapping single = ProjectPathMapping.singleRoot("N:/workspace-dev", CONTAINER);

        store.save(single);

        assertTrue(Files.readString(store.file()).contains("formatVersion=1"));
        assertEquals(single, store.loadOptional().orElseThrow());
        assertEquals(1, store.loadFormatVersion().orElseThrow());
    }

    @Test
    void aFormatOneFileOfAnEarlierVersionIsRead() throws IOException {
        write("formatVersion=1\nhostRoot=N\\:/workspace-dev\ncontainerRoot=/workspace/projects\n");

        assertEquals(ProjectPathMapping.singleRoot("N:/workspace-dev", CONTAINER),
                new ProjectPathMappingStore(home).loadOptional().orElseThrow());
    }

    @Test
    void anUnknownKeyIsRefused() throws IOException {
        write("formatVersion=2\ncontainerRoot=/workspace/projects\nmount.0.path=app\nmount.0.hostRoot=D\\:/a\n"
                + "mount.0.extra=x\n");

        IOException failure = assertThrows(IOException.class, () -> new ProjectPathMappingStore(home).loadOptional());
        assertTrue(failure.getMessage().contains("unknown project path mapping property"), failure.getMessage());
    }

    @Test
    void aHostRootKeyInFormatTwoAndMountKeysInFormatOneAreRefused() throws IOException {
        write("formatVersion=2\ncontainerRoot=/workspace/projects\nhostRoot=N\\:/workspace-dev\n");
        assertThrows(IOException.class, () -> new ProjectPathMappingStore(home).loadOptional());

        write("formatVersion=1\nhostRoot=N\\:/w\ncontainerRoot=/workspace/projects\nmount.0.path=app\n"
                + "mount.0.hostRoot=D\\:/a\n");
        assertThrows(IOException.class, () -> new ProjectPathMappingStore(home).loadOptional());
    }

    @Test
    void aGapInTheMountIndexesOrAHalfMountIsRefused() throws IOException {
        write("formatVersion=2\ncontainerRoot=/workspace/projects\nmount.1.path=app\nmount.1.hostRoot=D\\:/a\n");
        assertThrows(IOException.class, () -> new ProjectPathMappingStore(home).loadOptional());

        write("formatVersion=2\ncontainerRoot=/workspace/projects\nmount.0.path=app\n");
        assertThrows(IOException.class, () -> new ProjectPathMappingStore(home).loadOptional());
    }

    @Test
    void anOverlappingTableOnDiskIsRefused() throws IOException {
        write("formatVersion=2\ncontainerRoot=/workspace/projects\nmount.0.path=app\nmount.0.hostRoot=D\\:/a\n"
                + "mount.1.path=lib\nmount.1.hostRoot=D\\:/a/lib\n");

        assertThrows(IOException.class, () -> new ProjectPathMappingStore(home).loadOptional());
    }

    @Test
    void sixtyFourMountsFitTheBoundsAndAreReadBack() throws IOException {
        List<ProjectMount> mounts = new ArrayList<>();
        for (int index = 0; index < ProjectPathMapping.MAX_MOUNTS; index++) {
            mounts.add(new ProjectMount("project-" + index + "-" + "x".repeat(40),
                    "D:/a-rather-long-folder-name/for-the-bounds/" + "y".repeat(60) + "/project-" + index));
        }
        ProjectPathMappingStore store = new ProjectPathMappingStore(home);
        ProjectPathMapping table = ProjectPathMapping.mounted(CONTAINER, mounts);

        store.save(table);

        assertEquals(table, store.loadOptional().orElseThrow());
    }

    @Test
    void anUnknownFormatIsRefusedAndTheFileIsKept() throws IOException {
        write("formatVersion=3\ncontainerRoot=/workspace/projects\n");
        byte[] before = Files.readAllBytes(home.resolve("runtime").resolve(ProjectPathMappingStore.FILE_NAME));

        IOException failure = assertThrows(IOException.class, () -> new ProjectPathMappingStore(home).loadOptional());

        assertTrue(failure.getMessage().contains("unsupported project path mapping format"), failure.getMessage());
        assertArrayEquals(before, Files.readAllBytes(home.resolve("runtime").resolve(ProjectPathMappingStore.FILE_NAME)));
    }

    @Test
    void noFileMeansNoMappingAndNoFormat() throws IOException {
        ProjectPathMappingStore store = new ProjectPathMappingStore(home);

        assertTrue(store.loadOptional().isEmpty());
        assertTrue(store.loadFormatVersion().isEmpty());
    }
}
