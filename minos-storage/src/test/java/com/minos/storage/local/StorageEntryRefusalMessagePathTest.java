package com.minos.storage.local;

import com.minos.incremental.ProjectFingerprintService;
import com.minos.domain.registry.ProjectPathMapping;
import com.minos.storage.local.incremental.FileProjectFingerprintSnapshotStore;
import com.minos.storage.local.registry.InterProcessLocalProjectRegistry;
import com.minos.storage.local.registry.LocalProjectRegistry;
import com.minos.storage.local.store.FileSemanticVectorStore;
import com.minos.storage.local.store.FileSymbolSnapshotStore;
import com.minos.storage.local.store.SnapshotRetentionPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Registry entries, missing active snapshots and truncated indexes are refused by their file name, never by the
 * absolute path under MINOS_HOME (AUD-SEC-04).
 */
class StorageEntryRefusalMessagePathTest {

    private static final int SEMANTIC_MAGIC = 0x4D53454D;
    private static final int SEMANTIC_FORMAT_VERSION = 2;

    @TempDir
    Path temp;

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            rootRelativePath=project;rootPath=/legacy/project | registry project contains more than one root: %s
            rootRelativePath=project                          | portable project registry requires runtime path mapping: %s
            displayName=Project                               | missing registry project root in %s
            """)
    void aRegistryEntryWithoutAUsableRootIsRefusedByItsFileName(String properties, String expected) throws Exception {
        LocalProjectRegistry registry = new LocalProjectRegistry(temp.resolve("registry"));
        UUID id = UUID.randomUUID();
        writeEntry(id, properties.replace(';', '\n') + "\n");

        IOException refusal = assertThrows(IOException.class, () -> registry.findProject(id));

        assertEquals(expected.formatted(id + ".properties"), refusal.getMessage());
        assertNoAbsolutePath(refusal);
    }

    @Test
    void anUnsafePortableRootIsRefusedByItsFileName() throws Exception {
        Path hostRoot = Files.createDirectories(temp.resolve("host-projects")).toRealPath();
        Path dockerRoot = Files.createDirectories(temp.resolve("docker-projects")).toRealPath();
        LocalProjectRegistry registry = new LocalProjectRegistry(temp.resolve("registry"),
                new ProjectPathMapping(hostRoot.toString(), dockerRoot.toString()),
                ProjectPathMapping.RuntimeLocation.NATIVE);
        UUID id = UUID.randomUUID();
        writeEntry(id, "rootRelativePath=../escape\n");

        IOException refusal = assertThrows(IOException.class, () -> registry.findProject(id));

        assertEquals("invalid portable project root in " + id + ".properties", refusal.getMessage());
        assertNoAbsolutePath(refusal);
    }

    @Test
    void anEntryWhoseNameIsNotAUuidIsRefusedByItsFileName() throws Exception {
        LocalProjectRegistry registry = new LocalProjectRegistry(temp.resolve("registry"));
        Files.writeString(temp.resolve("registry").resolve("projects").resolve("not-a-uuid.properties"),
                "id=" + UUID.randomUUID() + "\n", StandardCharsets.UTF_8);

        IOException refusal = assertThrows(IOException.class, registry::listProjects);

        assertEquals("registry metadata filename is not a UUID: not-a-uuid.properties", refusal.getMessage());
        assertNoAbsolutePath(refusal);
    }

    @Test
    void aRegistryDirectoryReplacedByAFileIsRefusedByItsName() throws Exception {
        Path root = temp.resolve("registry");
        InterProcessLocalProjectRegistry registry = new InterProcessLocalProjectRegistry(root);
        Files.delete(root.resolve("workspaces"));
        Files.writeString(root.resolve("workspaces"), "x");

        IOException refusal = assertThrows(IOException.class, () -> registry.createWorkspace("Workspace"));

        assertEquals("workspace registry directory must be a non-symlink directory: workspaces", refusal.getMessage());
        assertNoAbsolutePath(refusal);
        assertTrue(Files.isRegularFile(root.resolve("workspaces")), "a refused mutation replaces nothing");
    }

    @Test
    void aMissingActiveFingerprintSnapshotIsRefusedByItsFileName() throws Exception {
        Path project = Files.createDirectories(temp.resolve("project").resolve("src"));
        Files.writeString(project.resolve("App.java"), "class App {}");
        Path storage = temp.resolve("fingerprints");
        FileProjectFingerprintSnapshotStore store = new FileProjectFingerprintSnapshotStore(storage);
        UUID projectId = UUID.randomUUID();
        store.publish(projectId, "index-1", new ProjectFingerprintService().capture(temp.resolve("project")));
        store.promote(projectId, "index-1");
        String snapshotName = deleteFilesStartingWith(storage.resolve(projectId.toString()), "fingerprint-");

        IOException load = assertThrows(IOException.class, () -> store.loadActive(projectId));
        IOException compact = assertThrows(IOException.class, () -> store.compact(projectId, java.util.Set.of(), 1));

        for (IOException refusal : List.of(load, compact)) {
            assertEquals("active fingerprint snapshot file is missing: " + snapshotName, refusal.getMessage());
            assertNoAbsolutePath(refusal);
        }
    }

    @Test
    void aMissingActiveSymbolSnapshotIsRefusedByItsFileName() throws Exception {
        Path storage = temp.resolve("snapshots");
        FileSymbolSnapshotStore store = new FileSymbolSnapshotStore(storage);
        UUID projectId = UUID.randomUUID();
        store.publish(projectId, "snapshot-1", List.of(), List.of(), List.of());
        String snapshotName = deleteFilesEndingWith(storage, ".knowledge");

        IOException refusal = assertThrows(IOException.class, () -> store.loadActiveReadView(projectId));

        assertEquals("active symbol snapshot file is missing: " + snapshotName, refusal.getMessage());
        assertNoAbsolutePath(refusal);
    }

    @Test
    void aRetentionWhoseActiveSnapshotIsMissingIsRefusedByItsFileName() throws Exception {
        FileSymbolSnapshotStore store = new FileSymbolSnapshotStore(temp.resolve("snapshots"));
        UUID projectId = UUID.randomUUID();
        store.publish(projectId, "snapshot-1", List.of(), List.of(), List.of());

        IOException refusal = assertThrows(IOException.class, () -> store.retentionService()
                .applyPolicy(projectId, "missing.knowledge", SnapshotRetentionPolicy.defaults()));

        assertEquals("active snapshot file is missing: missing.knowledge", refusal.getMessage());
        assertNoAbsolutePath(refusal);
    }

    @Test
    void aTruncatedSemanticIndexIsRefusedByItsFileName() throws Exception {
        Path root = temp.resolve("semantic");
        FileSemanticVectorStore store = new FileSemanticVectorStore(root);
        Path project = Files.createDirectories(root.resolve("project-1"));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(SEMANTIC_MAGIC);
            output.writeInt(SEMANTIC_FORMAT_VERSION);
        }
        Files.write(project.resolve("index-v2.bin"), bytes.toByteArray());

        IOException load = assertThrows(IOException.class, () -> store.load("project-1"));
        IOException metadata = assertThrows(IOException.class, () -> store.metadata("project-1"));

        assertEquals("truncated semantic index: index-v2.bin", load.getMessage());
        assertEquals("truncated semantic index metadata: index-v2.bin", metadata.getMessage());
        assertNoAbsolutePath(load);
        assertNoAbsolutePath(metadata);
    }

    private void writeEntry(UUID id, String roots) throws IOException {
        Files.writeString(temp.resolve("registry").resolve("projects").resolve(id + ".properties"),
                "id=" + id + "\n" + roots, StandardCharsets.UTF_8);
    }

    private static String deleteFilesStartingWith(Path directory, String prefix) throws IOException {
        String deleted = null;
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(path -> path.getFileName().toString().startsWith(prefix)).toList()) {
                deleted = file.getFileName().toString();
                Files.delete(file);
            }
        }
        assertNotNull(deleted, "a published snapshot file was expected");
        return deleted;
    }

    private static String deleteFilesEndingWith(Path root, String suffix) throws IOException {
        String deleted = null;
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(suffix)).toList()) {
                deleted = file.getFileName().toString();
                Files.delete(file);
            }
        }
        assertNotNull(deleted, "a published snapshot file was expected");
        return deleted;
    }

    private void assertNoAbsolutePath(Exception refusal) {
        assertFalse(refusal.getMessage().contains(temp.toString()), refusal.getMessage());
    }
}
