package com.minos.storage.local;

import com.minos.dynamic.CorrelatedRuntimeSession;
import com.minos.semantic.SemanticVectorStore;
import com.minos.storage.local.store.ProjectMutationSemanticVectorStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalStorageBackendTest {
    private static final List<String> NAMESPACES = List.of(
            "registry", "symbol-snapshots", "index-state", "fingerprint-snapshots",
            "semantic-index", "runtime-observations");

    @Test
    void refusesAMissingHome() {
        NullPointerException failure = assertThrows(NullPointerException.class, () -> new LocalStorageBackend(null));
        assertEquals("home", failure.getMessage());
    }

    @Test
    void createsEveryNamespaceUnderTheHomeAndWiresTheGuardedStores(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("minos-home");

        LocalStorageBackend backend = new LocalStorageBackend(home);

        assertEquals("local", backend.id());
        for (String namespace : NAMESPACES) {
            assertTrue(Files.isDirectory(home.resolve(namespace)), "missing namespace " + namespace);
        }
        try (var entries = Files.list(home)) {
            List<String> names = entries.map(path -> path.getFileName().toString()).sorted().toList();
            // The retention service owns one more directory, next to the six store namespaces.
            List<String> expected = java.util.stream.Stream.concat(NAMESPACES.stream(), java.util.stream.Stream.of(
                    "retention-locks")).sorted().toList();
            assertEquals(expected, names, "nothing else may be created under the MINOS home");
        }
        assertInstanceOf(SerializedRuntimeObservationStore.class, backend.runtimeObservationStore(),
                "runtime observations are serialized per project before the file locks");
        SemanticVectorStore semantic = backend.semanticVectorStore();
        assertInstanceOf(ProjectMutationSemanticVectorStore.class, semantic,
                "semantic mutations share the structural project mutation lease");
        assertTrue(backend.snapshotStore().loadActiveKnowledge(UUID.randomUUID()).isEmpty());
        assertTrue(backend.fingerprintStore().loadActive(UUID.randomUUID()).isEmpty());
        assertTrue(backend.projectRegistry().listWorkspaces().isEmpty());
    }

    @Test
    void anotherInstanceOverTheSameHomeSeesPersistedObservations(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("minos-home");
        UUID project = UUID.randomUUID();
        CorrelatedRuntimeSession session = RuntimeSessionFixtures.session(project, "run-1");

        new LocalStorageBackend(home).runtimeObservationStore().save(session);

        LocalStorageBackend reopened = new LocalStorageBackend(home);
        assertEquals(session, reopened.runtimeObservationStore().find(project, "run-1").orElseThrow());
        assertEquals(List.of(session), reopened.runtimeObservationStore().list(project));
    }

    @Test
    void refusesAHomeThatIsARegularFile(@TempDir Path temp) throws Exception {
        Path home = Files.writeString(temp.resolve("not-a-directory"), "occupied");

        assertThrows(IOException.class, () -> new LocalStorageBackend(home));

        assertEquals("occupied", Files.readString(home), "the occupying file must be left untouched");
    }

    @Test
    void refusesANamespaceOccupiedByARegularFile(@TempDir Path temp) throws Exception {
        Path home = Files.createDirectories(temp.resolve("minos-home"));
        Files.writeString(home.resolve("semantic-index"), "occupied");

        assertThrows(IOException.class, () -> new LocalStorageBackend(home));

        assertFalse(Files.isDirectory(home.resolve("semantic-index")));
        assertFalse(Files.exists(home.resolve("runtime-observations")),
                "construction stops at the first refused namespace");
    }
}
