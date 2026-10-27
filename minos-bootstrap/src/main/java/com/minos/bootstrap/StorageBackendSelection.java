package com.minos.bootstrap;

import com.minos.storage.local.LocalStorageBackend;
import com.minos.storage.StorageBackend;
import com.minos.storage.StorageBackendConfiguration;
import com.minos.storage.StorageBackendProvider;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.ServiceLoader;

/**
 * Selection of the configured storage backend (ADR 0042): the {@code local} backend is built in; any other backend,
 * optional, is discovered by {@code ServiceLoader}. No class of this module names the PostgreSQL backend (rule R1 of
 * check-module-boundaries.py, ADR 0068 section 4), its service is registered by the minos-app assembly only, and the
 * local composition loads none of its classes (LocalCompositionLoadsNoPostgresqlClassTest).
 *
 * <p>Discovery goes through the class loader that loaded MINOS (the one of the {@link StorageBackendProvider} port),
 * never through the thread context class loader, and refuses two providers of the requested identifier instead of
 * taking the first one (AUD-ARC-04).</p>
 */
final class StorageBackendSelection {
    private StorageBackendSelection() { }

    static StorageBackend open(StorageBackendConfiguration configuration) throws IOException {
        return open(configuration, minosClassLoader());
    }

    /** Variant with an explicit class loader, for the isolated class loader tests. */
    static StorageBackend open(StorageBackendConfiguration configuration, ClassLoader loader) throws IOException {
        if ("local".equals(configuration.backend())) {
            return new LocalStorageBackend(configuration.home());
        }
        return provider(configuration.backend(), loader).open(configuration);
    }

    static ClassLoader minosClassLoader() {
        return StorageBackendProvider.class.getClassLoader();
    }

    /** The only provider of the requested identifier (case-insensitive, {@link Locale#ROOT}), otherwise a refusal. */
    static StorageBackendProvider provider(String backend, ClassLoader loader) throws IOException {
        String requested = backend.toLowerCase(Locale.ROOT);
        List<StorageBackendProvider> matching = ServiceLoader.load(StorageBackendProvider.class, loader).stream()
                .map(ServiceLoader.Provider::get)
                .filter(provider -> requested.equals(provider.id().toLowerCase(Locale.ROOT)))
                .toList();
        if (matching.isEmpty()) {
            throw new IOException("MINOS storage backend provider is not available: " + backend);
        }
        if (matching.size() > 1) {
            List<String> classes = matching.stream().map(provider -> provider.getClass().getName()).sorted().toList();
            throw new IOException("MINOS storage backend " + backend + " has more than one provider: "
                    + String.join(", ", classes));
        }
        return matching.getFirst();
    }
}
