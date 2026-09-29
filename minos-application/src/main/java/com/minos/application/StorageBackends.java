package com.minos.application;

import com.minos.storage.StorageBackend;
import com.minos.storage.StorageBackendConfiguration;

import java.io.IOException;

/**
 * Resolves the configured MINOS storage backend without compile-time coupling to any backend.
 *
 * <p>ADR 0042 : la sélection (backend {@code local} intégré, puis fournisseurs découverts par
 * {@code ServiceLoader}) vit dans la racine de composition {@code minos-bootstrap} ; cette façade
 * garde le point d'entrée public et délègue à l'unique racine de composition.</p>
 */
public final class StorageBackends {
    private StorageBackends() { }

    public static StorageBackend open(StorageBackendConfiguration configuration) throws IOException {
        return MinosApplicationComposers.resolve().openStorageBackend(configuration);
    }
}
