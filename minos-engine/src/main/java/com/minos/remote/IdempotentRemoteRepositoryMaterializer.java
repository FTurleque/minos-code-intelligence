package com.minos.remote;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Gives every logical materialization acquisition a unique opaque object identity and makes release
 * idempotent for that identity. A reconstructed or structurally equal record cannot release, pin or
 * unpin another acquisition because only wrapper-issued instances are registered.
 */
public final class IdempotentRemoteRepositoryMaterializer implements RemoteRepositoryMaterializer {
    private final RemoteRepositoryMaterializer delegate;
    private final Object monitor = new Object();
    private final Map<RemoteMaterialization, RemoteMaterialization> active = new IdentityHashMap<>();

    private IdempotentRemoteRepositoryMaterializer(RemoteRepositoryMaterializer delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    public static RemoteRepositoryMaterializer wrap(RemoteRepositoryMaterializer delegate) {
        Objects.requireNonNull(delegate, "delegate");
        return delegate instanceof IdempotentRemoteRepositoryMaterializer ? delegate
                : new IdempotentRemoteRepositoryMaterializer(delegate);
    }

    @Override
    public RemoteMaterialization materialize(RemoteRepositoryRequest request) throws Exception {
        RemoteMaterialization owned = delegate.materialize(request);
        RemoteMaterialization exposed = copy(owned);
        synchronized (monitor) {
            active.put(exposed, owned);
        }
        return exposed;
    }

    @Override
    public void pin(RemoteMaterialization materialization) throws Exception {
        delegate.pin(requireOwned(materialization));
    }

    @Override
    public void unpin(RemoteMaterialization materialization) throws Exception {
        delegate.unpin(requireOwned(materialization));
    }

    @Override
    public void release(RemoteMaterialization materialization) throws Exception {
        Objects.requireNonNull(materialization, "materialization");
        RemoteMaterialization owned;
        synchronized (monitor) {
            owned = active.remove(materialization);
        }
        if (owned != null) delegate.release(owned);
    }

    int activeHandleCount() {
        synchronized (monitor) { return active.size(); }
    }

    private RemoteMaterialization requireOwned(RemoteMaterialization exposed) {
        Objects.requireNonNull(exposed, "materialization");
        synchronized (monitor) {
            RemoteMaterialization owned = active.get(exposed);
            if (owned == null) {
                throw new IllegalArgumentException("remote materialization handle is not an active acquisition");
            }
            return owned;
        }
    }

    private static RemoteMaterialization copy(RemoteMaterialization value) {
        return new RemoteMaterialization(value.request(), value.repositoryRoot(), value.projectRoot(),
                value.cacheKey(), value.cacheHit(), value.materializedAt());
    }
}
