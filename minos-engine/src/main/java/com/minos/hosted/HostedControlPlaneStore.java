package com.minos.hosted;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/** Tenant-addressed encrypted persistence port with optimistic concurrency. */
public interface HostedControlPlaneStore {
    void create(HostedTenantState state) throws IOException;

    Optional<HostedTenantState> find(UUID tenantId) throws IOException;

    void save(HostedTenantState state, long expectedVersion) throws IOException;
}
