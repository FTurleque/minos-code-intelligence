package com.minos.storage.local;

import com.minos.incremental.ProjectFingerprint;
import com.minos.io.PrivateLocalStorage;
import com.minos.io.PrivateLocalStorage.Privacy;
import com.minos.orchestration.ProjectIndexState;
import com.minos.registry.ProjectPathMapping;
import com.minos.storage.local.incremental.FileProjectFingerprintSnapshotStore;
import com.minos.storage.local.orchestration.FileIndexStateStore;
import com.minos.storage.local.registry.LocalProjectRegistry;
import com.minos.storage.local.registry.ProjectPathMappingStore;
import com.minos.storage.local.store.FileSymbolSnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S5: what the storage layer persists under MINOS_HOME derives from the user's code, so every
 * directory and file it creates -- including the files a temporary copy was promoted to -- is
 * owner-only, not left to the defaults of the platform.
 */
class LocalStorageTreesArePrivateTest {

    private static List<String> exposedEntries(Path root) throws IOException {
        List<String> exposed = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path entry : (Iterable<Path>) walk::iterator) {
                if (entry.equals(root)) continue;
                if (PrivateLocalStorage.privacyOf(entry) != Privacy.ENFORCED) {
                    exposed.add(root.relativize(entry).toString().replace('\\', '/'));
                }
            }
        }
        return exposed;
    }

    @Test
    void theProjectRegistryAndThePathMappingAreOwnerOnly(@TempDir Path temp) throws Exception {
        Path home = Files.createDirectories(temp.resolve("home"));
        Path hostRoot = Files.createDirectories(temp.resolve("host-projects")).toRealPath();
        Path dockerRoot = Files.createDirectories(temp.resolve("docker-projects")).toRealPath();
        Path project = Files.createDirectories(hostRoot.resolve("demo"));
        ProjectPathMapping mapping = new ProjectPathMapping(hostRoot.toString(), dockerRoot.toString());
        ProjectPathMappingStore mappings = new ProjectPathMappingStore(home);
        mappings.save(mapping);
        LocalProjectRegistry registry = new LocalProjectRegistry(
                home.resolve("registry"), mapping, ProjectPathMapping.RuntimeLocation.NATIVE);
        registry.registerProject(project, "Demo");
        registry.createWorkspace("team");

        assertEquals(Privacy.ENFORCED, PrivateLocalStorage.privacyOf(mappings.file()));
        List<String> exposed = exposedEntries(home);
        assertTrue(exposed.isEmpty(), "registry entries readable beyond their owner: " + exposed);
    }

    @Test
    void snapshotsFingerprintsAndIndexStateAreOwnerOnly(@TempDir Path home) throws Exception {
        FileSymbolSnapshotStore knowledge = new FileSymbolSnapshotStore(home.resolve("symbol-snapshots"));
        FileIndexStateStore states = new FileIndexStateStore(home.resolve("index-state"));
        FileProjectFingerprintSnapshotStore fingerprints =
                new FileProjectFingerprintSnapshotStore(home.resolve("fingerprint-snapshots"));
        UUID projectId = UUID.randomUUID();
        String emptySha256 = "e3b0c44298fc1c149afbf4c8996fb924" + "27ae41e4649b934ca495991b7852b855";

        knowledge.publish(projectId, "snapshot-1", List.of(), List.of(), List.of());
        fingerprints.publish(projectId, "snapshot-1", new ProjectFingerprint(emptySha256, emptySha256, List.of()));
        fingerprints.promote(projectId, "snapshot-1");
        states.saveProjectState(new ProjectIndexState(projectId, ProjectIndexState.Availability.READY,
                Optional.of("snapshot-1"), Optional.empty(), Instant.parse("2026-09-30T08:00:00Z"),
                Optional.empty()));

        List<String> exposed = exposedEntries(home);
        assertFalse(Files.list(home).findAny().isEmpty());
        assertTrue(exposed.isEmpty(), "persisted entries readable beyond their owner: " + exposed);
    }
}
