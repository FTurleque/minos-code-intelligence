package com.minos.application;

import com.minos.storage.StorageBackend;
import com.minos.storage.StorageBackendConfiguration;

import java.io.IOException;

/**
 * Resolves the configured MINOS storage backend without compile-time coupling to any backend.
 *
 * <p>ADR 0042: the selection (built-in {@code local} backend, then providers discovered by
 * {@code ServiceLoader}) lives in the composition root {@code minos-bootstrap}; this facade keeps the
 * public entry point and delegates to the single composition root.</p>
 */
public final class StorageBackends {
    private StorageBackends() { }

    public static StorageBackend open(StorageBackendConfiguration configuration) throws IOException {
        return MinosApplicationComposers.resolve().openStorageBackend(configuration);
    }
}
