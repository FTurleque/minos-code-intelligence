package com.minos.hosted;

import java.io.IOException;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/** Tenant-addressed encrypted persistence port with optimistic concurrency. */
public interface HostedControlPlaneStore {
    void create(HostedTenantState state) throws IOException;

    Optional<HostedTenantState> find(UUID tenantId) throws IOException;

    void save(HostedTenantState state, long expectedVersion) throws IOException;

    /**
     * Largest encoded size, in bytes, this store accepts for one tenant, or empty when it has no such
     * limit. The engine uses it to keep refusals from exhausting the room authorized mutations need; a
     * store that does not override it is not byte-bounded, so only the refusal count applies.
     */
    default OptionalLong tenantByteLimit() {
        return OptionalLong.empty();
    }
}
