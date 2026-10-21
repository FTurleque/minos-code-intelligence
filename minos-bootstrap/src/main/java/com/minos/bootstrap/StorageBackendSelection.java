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
 * Sélection du backend de stockage configuré (déplacée telle quelle de {@code StorageBackends.open},
 * ADR 0042) : le backend {@code local} est intégré ; tout autre backend, optionnel, est découvert par
 * {@code ServiceLoader} (PostgreSQL n'est donc jamais une dépendance de compilation de la composition).
 *
 * <p>La découverte passe par le chargeur qui a chargé MINOS (celui du port {@link StorageBackendProvider}),
 * jamais par le chargeur de contexte du thread, et refuse deux fournisseurs de l'identifiant demandé au lieu
 * de prendre le premier (AUD-ARC-04).</p>
 */
final class StorageBackendSelection {
    private StorageBackendSelection() { }

    static StorageBackend open(StorageBackendConfiguration configuration) throws IOException {
        return open(configuration, minosClassLoader());
    }

    /** Variante à chargeur explicite, pour les tests de chargeur isolé. */
    static StorageBackend open(StorageBackendConfiguration configuration, ClassLoader loader) throws IOException {
        if ("local".equals(configuration.backend())) {
            return new LocalStorageBackend(configuration.home());
        }
        return provider(configuration.backend(), loader).open(configuration);
    }

    static ClassLoader minosClassLoader() {
        return StorageBackendProvider.class.getClassLoader();
    }

    /** L'unique fournisseur de l'identifiant demandé (sans casse, {@link Locale#ROOT}), sinon un refus. */
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
