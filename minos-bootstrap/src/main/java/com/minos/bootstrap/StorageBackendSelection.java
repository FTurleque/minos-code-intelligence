package com.minos.bootstrap;

import com.minos.storage.LocalStorageBackend;
import com.minos.storage.StorageBackend;
import com.minos.storage.StorageBackendConfiguration;
import com.minos.storage.StorageBackendProvider;

import java.io.IOException;
import java.util.ServiceLoader;

/**
 * Sélection du backend de stockage configuré (déplacée telle quelle de {@code StorageBackends.open},
 * ADR 0042) : le backend {@code local} est intégré ; tout autre backend, optionnel, est découvert par
 * {@code ServiceLoader} (PostgreSQL n'est donc jamais une dépendance de compilation de la composition).
 */
final class StorageBackendSelection {
    private StorageBackendSelection() { }

    static StorageBackend open(StorageBackendConfiguration configuration) throws IOException {
        if ("local".equals(configuration.backend())) {
            return new LocalStorageBackend(configuration.home());
        }
        for (StorageBackendProvider provider : ServiceLoader.load(StorageBackendProvider.class)) {
            if (configuration.backend().equalsIgnoreCase(provider.id())) {
                return provider.open(configuration);
            }
        }
        throw new IOException("MINOS storage backend provider is not available: " + configuration.backend());
    }
}
