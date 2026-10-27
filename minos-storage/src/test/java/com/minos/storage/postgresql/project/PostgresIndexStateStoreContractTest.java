package com.minos.storage.postgresql.project;

import com.minos.orchestration.IndexStateStore;
import com.minos.orchestration.IndexStateStoreContract;
import com.minos.storage.postgresql.PostgresTestSupport;
import com.minos.storage.postgresql.support.PostgresJsonCodec;

/** The PostgreSQL backend against the contract shared by every index state store. */
class PostgresIndexStateStoreContractTest extends PostgresTestSupport implements IndexStateStoreContract {

    @Override
    public IndexStateStore newStore() {
        return new PostgresIndexStateStore(connections, new PostgresJsonCodec());
    }

    @Override
    public IndexStateStore reopen() {
        return new PostgresIndexStateStore(connections, new PostgresJsonCodec());
    }
}
