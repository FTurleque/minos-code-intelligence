package com.minos.orchestration;

/** Provider extension contract independent from runtime/install implementation details. */
public interface IndexerProvider {
    IndexerDescriptor descriptor();
    ProviderCapabilityProfile capabilityProfile();

    /**
     * M24 operational evidence. The default preserves source compatibility for
     * third-party providers created before M24, while production catalog entries
     * are required by M24 conformance gates to return an explicit profile.
     */
    default ProviderOperationalProfile operationalProfile() {
        return ProviderOperationalProfile.legacy(descriptor().id());
    }
}
