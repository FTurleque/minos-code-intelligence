package com.minos.storage.local.orchestration;

import com.minos.orchestration.IndexStateStore;
import com.minos.orchestration.IndexStateStoreContract;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/** The file backend against the contract shared by every index state store. */
class FileIndexStateStoreContractTest implements IndexStateStoreContract {

    @TempDir
    Path root;

    @Override
    public IndexStateStore newStore() throws Exception {
        return new FileIndexStateStore(root.resolve("state"));
    }

    @Override
    public IndexStateStore reopen() throws Exception {
        return new FileIndexStateStore(root.resolve("state"));
    }
}
