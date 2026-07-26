package com.minos.orchestration;

/** Provider extension contract independent from runtime/install implementation details. */
public interface IndexerProvider {
    IndexerDescriptor descriptor();
    ProviderCapabilityProfile capabilityProfile();
}
