package com.minos.storage.postgresql;

import com.minos.storage.StorageBackend;
import com.minos.storage.StorageBackendConfiguration;
import com.minos.storage.StorageBackendProvider;

import java.io.IOException;

public final class PostgresStorageBackendProvider implements StorageBackendProvider {
    @Override public String id() { return "postgresql"; }

    @Override
    public StorageBackend open(StorageBackendConfiguration configuration) throws IOException {
        PostgresConnectionFactory connections = new PostgresConnectionFactory(configuration);
        try {
            return new PostgresStorageBackend(connections, configuration.home());
        } catch (IOException | RuntimeException exception) {
            connections.close();
            throw exception;
        }
    }
}
