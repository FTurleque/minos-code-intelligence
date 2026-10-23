package com.minos.application.remote;

import com.minos.remote.DistributedIndexing.WorkerNetworkPolicy;

/**
 * The network policy a remote indexing asks of its worker, with one value per value of the engine policy and the
 * same name (AUD-ARC-01, D2); {@code RemoteSourceRequestTest} fails on a value present on one side only.
 */
public enum WorkerNetworkChoice {
    DENY,
    ALLOW;

    /** The engine policy of the same name. */
    WorkerNetworkPolicy policy() {
        return WorkerNetworkPolicy.valueOf(name());
    }
}
