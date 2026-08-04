package com.minos.storage;

import java.io.IOException;
import java.util.ServiceLoader;

/** Resolves the configured MINOS storage backend without compile-time coupling to optional providers. */
public final class StorageBackends {
    private StorageBackends() { }

    public static StorageBackend open(StorageBackendConfiguration configuration) throws IOException {
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
