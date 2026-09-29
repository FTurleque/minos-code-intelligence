package com.minos.characterization;

import com.minos.application.MinosApplication;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.InMemoryIndexStateStore;
import com.minos.runtime.ProviderRuntimeStatus;
import com.minos.storage.local.LocalStorageBackend;
import com.minos.storage.StorageBackend;
import com.minos.storage.StorageBackendConfiguration;
import com.minos.storage.StorageBackendProvider;
import com.minos.storage.StorageBackends;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A2 — caractérisation de la composition : ordre dans lequel l'assembleur consulte le backend de
 * stockage, repli de la rétention, fermeture sur échec, idempotence de {@code close()}, sélection
 * du backend et catalogues par défaut. Ces faits doivent rester identiques quand la racine de
 * composition et les adaptateurs changent de module.
 */
class A2CompositionCharacterizationTest {

    /** Ordre exact des appels de l'assembleur au backend choisi, puis contrôle d'identité par l'application. */
    private static final List<String> BACKEND_CONSULTATION_ORDER = List.of(
            "projectRegistry", "snapshotStore", "indexStateStore", "fingerprintStore",
            "semanticVectorStore", "runtimeObservationStore", "retentionService", "id");

    @Test
    void assemblerConsultsTheSelectedBackendInAFixedOrderAndCloseIsIdempotent(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        List<String> calls = new ArrayList<>();
        StorageBackend backend = recording(new LocalStorageBackend(home), calls, null);

        MinosApplication application = MinosApplication.builder(home).storageBackend(backend).build();

        assertEquals(BACKEND_CONSULTATION_ORDER, calls);
        assertEquals("local", application.storageBackendId());
        calls.clear();
        application.close();
        application.close();
        assertEquals(List.of("close"), calls);
    }

    @Test
    void overridingAStoreSkipsBackendRetentionAndKeepsTheBackendStores(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        List<String> calls = new ArrayList<>();
        LocalStorageBackend local = new LocalStorageBackend(home);
        StorageBackend backend = recording(local, calls, null);
        InMemoryIndexStateStore override = new InMemoryIndexStateStore();

        try (MinosApplication application = MinosApplication.builder(home)
                .storageBackend(backend)
                .indexStateStore(override)
                .build()) {
            // Le magasin surchargé n'est pas demandé au backend, et la rétention du backend non plus.
            assertEquals(List.of("projectRegistry", "snapshotStore", "fingerprintStore",
                    "semanticVectorStore", "runtimeObservationStore", "id"), calls);
            assertSame(override, application.indexStateStore());
            assertSame(local.projectRegistry(), application.projectRegistry());
            assertNotSame(local.retentionService(), application.retentionService());
            assertEquals("RetentionResult[deletedKnowledgeSnapshots=0, deletedFingerprintSnapshots=0, deletedIndexingRuns=0]",
                    String.valueOf(application.retentionService().compact(UUID.randomUUID())));
        }
    }

    @Test
    void aFailureDuringAssemblyClosesTheSelectedBackendExactlyOnce(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        List<String> calls = new ArrayList<>();
        StorageBackend backend = recording(new LocalStorageBackend(home), calls, " ");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> MinosApplication.builder(home).storageBackend(backend).build());

        assertEquals("storageBackendId must not be blank", failure.getMessage());
        assertEquals(BACKEND_CONSULTATION_ORDER, calls.subList(0, BACKEND_CONSULTATION_ORDER.size()));
        assertEquals(List.of("close"), calls.subList(BACKEND_CONSULTATION_ORDER.size(), calls.size()));
    }

    @Test
    void openSelectsTheLocalBackendAndCreatesTheSameHomeLayout(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        try (MinosApplication application = MinosApplication.open(home)) {
            assertEquals("local", application.storageBackendId());
            try (Stream<Path> children = Files.list(home)) {
                // Six espaces du backend local, plus ceux que la composition par défaut prépare
                // (verrous de rétention, runs, snapshots préparés).
                assertEquals(List.of("fingerprint-snapshots", "index-state", "registry", "retention-locks", "runs",
                                "runtime-observations", "semantic-index", "staged-snapshots", "symbol-snapshots"),
                        children.map(path -> path.getFileName().toString()).sorted().toList());
            }
            assertEquals(List.of("scip-java", "scip-typescript", "scip-python", "scip-clang", "scip-dotnet",
                            "scip-go", "rust-analyzer-scip"),
                    application.indexerDescriptors().stream().map(IndexerDescriptor::id).toList());
            assertEquals(List.of("rust-analyzer-scip", "scip-clang", "scip-dotnet", "scip-go", "scip-java",
                            "scip-python", "scip-typescript"),
                    application.providerRuntimeManager().list().stream().map(ProviderRuntimeStatus::providerId).toList());
        }
    }

    @Test
    void backendSelectionNormalizesTheLocalNameAndDiscoversOnlyPostgresqlByServiceLoader(@TempDir Path temp)
            throws Exception {
        Path home = temp.resolve("home");
        StorageBackendConfiguration mixedCase =
                new StorageBackendConfiguration(" Local ", home, null, null, null, null);
        assertEquals("local", mixedCase.backend());
        try (StorageBackend backend = StorageBackends.open(mixedCase)) {
            assertEquals(LocalStorageBackend.class, backend.getClass());
        }
        IllegalArgumentException unsupported = assertThrows(IllegalArgumentException.class,
                () -> new StorageBackendConfiguration("sqlite", home, null, null, null, null));
        assertEquals("unsupported storage backend: sqlite", unsupported.getMessage());
        assertEquals(List.of("postgresql"), ServiceLoader.load(StorageBackendProvider.class).stream()
                .map(provider -> provider.get().id())
                .toList());
    }

    private static StorageBackend recording(StorageBackend delegate, List<String> calls, String idOverride) {
        InvocationHandler handler = (proxy, method, arguments) -> {
            calls.add(method.getName());
            if ("id".equals(method.getName()) && idOverride != null) return idOverride;
            try {
                return method.invoke(delegate, arguments);
            } catch (InvocationTargetException exception) {
                throw exception.getCause();
            }
        };
        return (StorageBackend) Proxy.newProxyInstance(
                StorageBackend.class.getClassLoader(), new Class<?>[]{StorageBackend.class}, handler);
    }
}
