package com.minos.orchestration;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Registry of provider extensions; consumers can derive legacy descriptors without provider branching. */
public final class IndexerProviderRegistry {
    private final Map<String, IndexerProvider> providers = new LinkedHashMap<>();

    public synchronized void register(IndexerProvider provider) {
        Objects.requireNonNull(provider, "provider");
        String id = provider.descriptor().id();
        if (!provider.capabilityProfile().providerId().equals(id)) {
            throw new IllegalArgumentException("provider descriptor/profile id mismatch: " + id);
        }
        if (providers.putIfAbsent(id, provider) != null) {
            throw new IllegalArgumentException("Provider already registered: " + id);
        }
    }

    public synchronized void registerAll(Iterable<? extends IndexerProvider> values) {
        Objects.requireNonNull(values, "values");
        for (IndexerProvider value : values) {
            register(value);
        }
    }

    public synchronized Optional<IndexerProvider> find(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("providerId must not be blank");
        }
        return Optional.ofNullable(providers.get(providerId));
    }

    public synchronized List<IndexerProvider> list() {
        return providers.values().stream()
                .sorted(Comparator.comparing(value -> value.descriptor().id()))
                .toList();
    }

    public synchronized List<IndexerDescriptor> descriptors() {
        return list().stream().map(IndexerProvider::descriptor).toList();
    }

    public synchronized List<ProviderCapabilityProfile> capabilityProfiles() {
        return list().stream().map(IndexerProvider::capabilityProfile).toList();
    }

    public synchronized IndexerRegistry negotiationRegistry() {
        IndexerRegistry registry = new IndexerRegistry();
        registry.registerAll(descriptors());
        return registry;
    }
}
