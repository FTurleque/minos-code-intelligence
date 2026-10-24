package com.minos.nexus;

import com.minos.registry.ProjectRegistry;
import com.minos.store.CodeKnowledgeSnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The export refuses a project root by its leaf name, never by its absolute path (AUD-SEC-04). */
class NexusExportRefusalMessageTest {

    @TempDir
    Path temp;

    @Test
    void anUnregisteredProjectRootIsRefusedByItsName() throws Exception {
        Path root = Files.createDirectories(temp.resolve("unregistered-project"));
        NexusExportService service = new NexusExportService(emptyRegistry(), untouchedSnapshots());

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class, () -> service.export(root));

        assertEquals("project root is not registered in MINOS: unregistered-project", refusal.getMessage());
        assertFalse(refusal.getMessage().contains(temp.toString()), refusal.getMessage());
    }

    @Test
    void aProjectRootThatIsARegularFileIsRefusedByItsName() throws Exception {
        Path file = Files.writeString(temp.resolve("not-a-directory.txt"), "x");
        NexusExportService service = new NexusExportService(emptyRegistry(), untouchedSnapshots());

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class, () -> service.export(file));

        assertEquals("projectRoot must be an existing directory: not-a-directory.txt", refusal.getMessage());
        assertFalse(refusal.getMessage().contains(temp.toString()), refusal.getMessage());
    }

    private static ProjectRegistry emptyRegistry() {
        return (ProjectRegistry) Proxy.newProxyInstance(ProjectRegistry.class.getClassLoader(),
                new Class<?>[] {ProjectRegistry.class}, (proxy, method, args) -> {
                    if (method.getName().equals("listProjects")) return List.of();
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static CodeKnowledgeSnapshotStore untouchedSnapshots() {
        return (CodeKnowledgeSnapshotStore) Proxy.newProxyInstance(CodeKnowledgeSnapshotStore.class.getClassLoader(),
                new Class<?>[] {CodeKnowledgeSnapshotStore.class}, (proxy, method, args) -> {
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
