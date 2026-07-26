package com.minos.runtime;

import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Provider-neutral runtime facade composed from independent provider extensions.
 * Public commands never branch on provider ids.
 */
public final class CompositeProviderRuntimeManager implements ProviderRuntimeManager {
    private final Map<String, ProviderRuntimeManager> delegatesByProvider;

    public CompositeProviderRuntimeManager(List<? extends ProviderRuntimeManager> delegates) {
        Objects.requireNonNull(delegates, "delegates");
        Map<String, ProviderRuntimeManager> values = new LinkedHashMap<>();
        for (ProviderRuntimeManager delegate : delegates) {
            Objects.requireNonNull(delegate, "delegate");
            for (ProviderRuntimeStatus status : delegate.list()) {
                ProviderRuntimeManager previous = values.putIfAbsent(status.providerId(), delegate);
                if (previous != null) {
                    throw new IllegalArgumentException("duplicate provider runtime extension: " + status.providerId());
                }
            }
        }
        this.delegatesByProvider = Map.copyOf(values);
    }

    @Override
    public List<ProviderRuntimeStatus> list() {
        return delegatesByProvider.keySet().stream()
                .sorted()
                .map(this::inspect)
                .toList();
    }

    @Override
    public ProviderRuntimeStatus inspect(String providerId) {
        return delegate(providerId).inspect(providerId);
    }

    @Override
    public ProviderRuntimeStatus install(String providerId) throws Exception {
        return delegate(providerId).install(providerId);
    }

    @Override
    public IndexerExecutor executor(String providerId) {
        return delegate(providerId).executor(providerId);
    }

    private ProviderRuntimeManager delegate(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("providerId must not be blank");
        }
        ProviderRuntimeManager delegate = delegatesByProvider.get(providerId);
        if (delegate == null) {
            throw new IllegalArgumentException("unknown managed provider: " + providerId);
        }
        return delegate;
    }
}
