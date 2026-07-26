package com.minos.orchestration;

import com.minos.discovery.ProjectDiscovery.BuildSystem;
import com.minos.discovery.ProjectDiscovery.Language;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderConformanceKitTest {

    @Test
    void rejectsAnyImplicitCapability() {
        EnumMap<IndexerCapability, CapabilitySupportLevel> support = exhaustive(CapabilitySupportLevel.UNSUPPORTED);
        support.remove(IndexerCapability.CALL_RELATIONS);
        assertThrows(IllegalArgumentException.class,
                () -> new ProviderCapabilityProfile("fixture", support, List.of()));
    }

    @Test
    void producesDeterministicExhaustiveProfile() {
        EnumMap<IndexerCapability, CapabilitySupportLevel> support = exhaustive(CapabilitySupportLevel.UNSUPPORTED);
        support.put(IndexerCapability.SYMBOLS, CapabilitySupportLevel.FULL);
        support.put(IndexerCapability.REFERENCES, CapabilitySupportLevel.PARTIAL);
        IndexerProvider provider = provider("fixture", support);

        ProviderConformanceKit.ConformanceResult result = new ProviderConformanceKit().evaluate(provider);
        assertEquals(IndexerCapability.values().length, result.capabilities().size());
        assertEquals("FULL", result.capabilities().get("SYMBOLS"));
        assertEquals("PARTIAL", result.capabilities().get("REFERENCES"));
        assertTrue(result.scorePercent() > 0);
        assertEquals(result, new ProviderConformanceKit().evaluate(provider));
    }

    @Test
    void providerRegistryRejectsDescriptorProfileMismatchAndDuplicates() {
        IndexerProviderRegistry registry = new IndexerProviderRegistry();
        IndexerProvider provider = provider("fixture", exhaustive(CapabilitySupportLevel.FULL));
        registry.register(provider);
        assertEquals(List.of("fixture"), registry.descriptors().stream().map(IndexerDescriptor::id).toList());
        assertThrows(IllegalArgumentException.class, () -> registry.register(provider));

        IndexerProvider mismatched = new IndexerProvider() {
            @Override public IndexerDescriptor descriptor() { return ProviderConformanceKitTest.descriptor("other"); }
            @Override public ProviderCapabilityProfile capabilityProfile() {
                return new ProviderCapabilityProfile("profile", exhaustive(CapabilitySupportLevel.FULL), List.of());
            }
        };
        assertThrows(IllegalArgumentException.class, () -> new IndexerProviderRegistry().register(mismatched));
    }

    private static IndexerProvider provider(String id, Map<IndexerCapability, CapabilitySupportLevel> support) {
        IndexerDescriptor descriptor = descriptor(id);
        ProviderCapabilityProfile profile = new ProviderCapabilityProfile(id, support, List.of("fixture limitation"));
        return new IndexerProvider() {
            @Override public IndexerDescriptor descriptor() { return descriptor; }
            @Override public ProviderCapabilityProfile capabilityProfile() { return profile; }
        };
    }

    private static IndexerDescriptor descriptor(String id) {
        return new IndexerDescriptor(
                id, "1.0", id, Set.of(Language.JAVA), Set.of(BuildSystem.MAVEN),
                Set.of(IndexerCapability.SYMBOLS), IndexerQualification.QUALIFIED_WITH_CONSTRAINTS,
                10, List.of("fixture limitation"));
    }

    private static EnumMap<IndexerCapability, CapabilitySupportLevel> exhaustive(CapabilitySupportLevel level) {
        EnumMap<IndexerCapability, CapabilitySupportLevel> values = new EnumMap<>(IndexerCapability.class);
        for (IndexerCapability capability : IndexerCapability.values()) {
            values.put(capability, level);
        }
        return values;
    }
}
