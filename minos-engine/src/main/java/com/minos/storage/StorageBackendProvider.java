package com.minos.storage;

import java.io.IOException;

/** Service-provider contract for non-local MINOS persistence backends. */
public interface StorageBackendProvider {

    String id();

    StorageBackend open(StorageBackendConfiguration configuration) throws IOException;
}
