package com.minos.storage.local.registry;

import com.minos.domain.registry.ProjectPathMapping;
import com.minos.registry.ProjectPathMappings;
import com.minos.storage.MinosRuntimeSettings;
import com.minos.storage.StorageBackendConfiguration;
import com.minos.storage.home.ProjectPathMappingStore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Project path mapping of one MINOS home: the file store for the mapping itself, and the local registry for the
 * projects a replacement re-expresses ({@link ProjectRootReplacement}).
 */
public final class LocalProjectPathMappings implements ProjectPathMappings {

    /** Suffix of the single root mapping file saved by {@link #migrateToTable}. */
    public static final String FORMAT1_BACKUP_SUFFIX = ".format1.bak";

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
        if (desired.mountTable() || store.loadOptional().map(ProjectPathMapping::mountTable).orElse(false)) {
            throw new IOException("project root replacement refused: the Docker runtime exposes one mount per project; "
                    + "expose a project with mcp-lifecycle.ps1 -Action AddProject");
        }
        if (postgresqlBackend()) {
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

    @Override
    public List<RegisteredRoot> registeredRoots() throws IOException {
        ProjectPathMapping existing = requireSingleRoot();
        return new InterProcessLocalProjectRegistry(home.resolve(InterProcessLocalProjectRegistry.NAMESPACE))
                .registeredRoots(existing);
    }

    @Override
    public void migrateToTable(ProjectPathMapping table) throws IOException {
        Objects.requireNonNull(table, "table");
        if (!table.mountTable()) throw new IllegalArgumentException("the migration target must be a mount table");
        ProjectPathMapping existing = requireSingleRoot();
        if (!existing.containerRoot().equals(table.containerRoot())) {
            throw new IOException("project mount migration refused: the container root changes");
        }
        store.backupBeside(FORMAT1_BACKUP_SUFFIX);
        store.save(table);
    }

    /** True when the runtime settings of this home select the PostgreSQL storage backend. */
    public boolean postgresqlBackend() throws IOException {
        String backend = MinosRuntimeSettings.load(home)
                .value(StorageBackendConfiguration.BACKEND_PROPERTY, StorageBackendConfiguration.BACKEND_ENV);
        return backend != null && "postgresql".equals(backend.toLowerCase(Locale.ROOT));
    }

    private ProjectPathMapping requireSingleRoot() throws IOException {
        Optional<ProjectPathMapping> saved = store.loadOptional();
        if (saved.isEmpty() || saved.orElseThrow().mountTable()) {
            throw new IOException("project mount migration refused: no single root project mapping is saved");
        }
        return saved.orElseThrow();
    }
}
