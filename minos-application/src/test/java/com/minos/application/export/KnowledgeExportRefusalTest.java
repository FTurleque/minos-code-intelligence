package com.minos.application.export;

import com.minos.domain.registry.DegradedEntry;
import com.minos.domain.registry.UnreadableRegistryException;
import com.minos.registry.ProjectRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The export refuses by leaf name, never by absolute path, and stays strict on an unreadable registry. */
class KnowledgeExportRefusalTest {

    @TempDir
    Path temp;

    @Test
    void anUnregisteredProjectRootIsRefusedByItsName() throws Exception {
        Path root = Files.createDirectories(temp.resolve("unregistered-project"));
        KnowledgeExportUseCase useCase = new KnowledgeExportUseCase(ExportFixture.registry(List.of()), untouched());

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class, () -> useCase.export(root));

        assertEquals("project root is not registered in MINOS: unregistered-project", refusal.getMessage());
        assertFalse(refusal.getMessage().contains(temp.toString()), refusal.getMessage());
    }

    @Test
    void aProjectRootThatIsARegularFileIsRefusedByItsName() throws Exception {
        Path file = Files.writeString(temp.resolve("not-a-directory.txt"), "x");
        KnowledgeExportUseCase useCase = new KnowledgeExportUseCase(ExportFixture.registry(List.of()), untouched());

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class, () -> useCase.export(file));

        assertEquals("projectRoot must be an existing directory: not-a-directory.txt", refusal.getMessage());
        assertFalse(refusal.getMessage().contains(temp.toString()), refusal.getMessage());
    }

    @Test
    void aProjectWithoutActiveSnapshotIsRefusedByItsDisplayName() throws Exception {
        ExportFixture fixture = new ExportFixture(temp);
        KnowledgeExportUseCase useCase = new KnowledgeExportUseCase(
                ExportFixture.registry(List.of(fixture.project)), ExportFixture.store(Optional.empty()));

        IllegalStateException refusal = assertThrows(IllegalStateException.class, () -> useCase.export(fixture.root));

        assertEquals("project has no active MINOS knowledge snapshot: demo", refusal.getMessage());
    }

    @Test
    void anUnreadableRegistryEntryIsExplainedWithTheReadFailureSuppressed() throws Exception {
        IOException failure = new IOException("broken entry");
        UnreadableRegistryException explained = UnreadableRegistryException.of(
                List.of(new DegradedEntry("entry", "unreadable")), "the project of this root cannot be located with certainty");
        KnowledgeExportUseCase useCase = new KnowledgeExportUseCase(failingRegistry(failure, Optional.of(explained)),
                untouched());

        UnreadableRegistryException thrown = assertThrows(UnreadableRegistryException.class,
                () -> useCase.export(temp));

        assertSame(explained, thrown);
        assertEquals("1 registry entry is unreadable, so the project of this root cannot be located with certainty",
                thrown.getMessage());
        assertArrayEquals(new Throwable[] {failure}, thrown.getSuppressed());
    }

    @Test
    void aRegistryFailureThatIsNotExplainedPropagatesAsIs() throws Exception {
        IllegalStateException failure = new IllegalStateException("backend failure");
        KnowledgeExportUseCase useCase = new KnowledgeExportUseCase(failingRegistry(failure, Optional.empty()),
                untouched());

        assertSame(failure, assertThrows(IllegalStateException.class, () -> useCase.export(temp)));
    }

    @Test
    void anInterruptedReadIsNotExplained() throws Exception {
        IOException failure = new IOException("interrupted");
        KnowledgeExportUseCase useCase = new KnowledgeExportUseCase(failingRegistry(failure, null), untouched());

        Thread.currentThread().interrupt();
        try {
            assertSame(failure, assertThrows(IOException.class, () -> useCase.export(temp)));
        } finally {
            assertTrue(Thread.interrupted());
        }
    }

    @Test
    void collaboratorsAreRequired() {
        assertThrows(NullPointerException.class, () -> new KnowledgeExportUseCase(null, untouched()));
        assertThrows(NullPointerException.class,
                () -> new KnowledgeExportUseCase(ExportFixture.registry(List.of()), null));
        assertThrows(NullPointerException.class, () -> KnowledgeExportUseCase.of(null));
        KnowledgeExportUseCase useCase = new KnowledgeExportUseCase(ExportFixture.registry(List.of()), untouched());
        assertThrows(NullPointerException.class, () -> useCase.export(null));
    }

    /** A registry whose strict read fails; {@code explanation} null means it must not be asked. */
    private static ProjectRegistry failingRegistry(Exception failure,
                                                   Optional<UnreadableRegistryException> explanation) {
        return (ProjectRegistry) Proxy.newProxyInstance(ProjectRegistry.class.getClassLoader(),
                new Class<?>[] {ProjectRegistry.class}, (proxy, method, args) -> {
                    if (method.getName().equals("listProjects")) throw failure;
                    if (method.getName().equals("explainUnreadable") && explanation != null) return explanation;
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static com.minos.store.CodeKnowledgeSnapshotStore untouched() {
        return (com.minos.store.CodeKnowledgeSnapshotStore) Proxy.newProxyInstance(
                com.minos.store.CodeKnowledgeSnapshotStore.class.getClassLoader(),
                new Class<?>[] {com.minos.store.CodeKnowledgeSnapshotStore.class}, (proxy, method, args) -> {
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
