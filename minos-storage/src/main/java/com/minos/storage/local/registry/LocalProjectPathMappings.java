package com.minos.storage.local.registry;

import com.minos.domain.registry.ProjectPathMapping;
import com.minos.registry.ProjectPathMappings;
import com.minos.storage.MinosRuntimeSettings;
import com.minos.storage.StorageBackendConfiguration;
import com.minos.storage.home.ProjectPathMappingStore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Project path mapping of one MINOS home: the file store for the mapping itself, and the local registry for the
 * projects a replacement re-expresses ({@link ProjectRootReplacement}).
 */
public final class LocalProjectPathMappings implements ProjectPathMappings {

    private final Path home;
    private final ProjectPathMappingStore store;

    public LocalProjectPathMappings(Path home) {
        this.home = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
        this.store = new ProjectPathMappingStore(this.home);
    }

    @Override
    public Optional<ProjectPathMapping> loadOptional() throws IOException {
        return store.loadOptional();
    }

    @Override
    public void save(ProjectPathMapping mapping) throws IOException {
        store.save(mapping);
    }

    @Override
    public Replacement replace(ProjectPathMapping desired) throws IOException {
        Objects.requireNonNull(desired, "desired");
        String backend = MinosRuntimeSettings.load(home)
                .value(StorageBackendConfiguration.BACKEND_PROPERTY, StorageBackendConfiguration.BACKEND_ENV);
        if (backend != null && "postgresql".equals(backend.toLowerCase(Locale.ROOT))) {
            // The PostgreSQL registry also stores roots relative to the mapping, and this plane cannot reach it.
            throw new IOException("project root replacement refused: the projects are held by PostgreSQL storage, "
                    + "which this replacement cannot rebase; keep the recorded projects root, or uninstall the Docker "
                    + "runtime with its data and install it again with the new root");
        }
        if (store.loadOptional().isEmpty()) {
            store.save(desired);
            return new Replacement(0, 0, 0);
        }
        return new InterProcessLocalProjectRegistry(home.resolve(InterProcessLocalProjectRegistry.NAMESPACE))
                .replaceRoot(store, desired);
    }
}
