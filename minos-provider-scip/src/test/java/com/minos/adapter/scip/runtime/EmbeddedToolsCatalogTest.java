package com.minos.adapter.scip.runtime;

import com.minos.adapter.scip.ScipIndexerCatalog;
import com.minos.orchestration.IndexerDescriptor;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The single description of the shipped tools agrees with the provider catalogue and with itself. */
class EmbeddedToolsCatalogTest {

    private final EmbeddedToolsCatalog catalog = EmbeddedToolsCatalog.load();

    @Test
    void everyProviderVersionOfTheToolsCatalogueIsTheOneOfTheProviderCatalogue() {
        for (IndexerDescriptor descriptor : ScipIndexerCatalog.qualifiedM24Descriptors()) {
            assertEquals(descriptor.version(), catalog.provider(descriptor.id()).version(),
                    "version of " + descriptor.id() + " differs between the two descriptions");
        }
        assertEquals(ManagedScipProviderRuntimeManager.SCIP_JAVA_VERSION, catalog.provider("scip-java").version());
        assertEquals(ManagedScipProviderRuntimeManager.SCIP_TYPESCRIPT_VERSION, catalog.provider("scip-typescript").version());
        assertEquals(ManagedScipPythonRuntimeManager.VERSION, catalog.provider("scip-python").version());
    }

    @Test
    void theCatalogueCoversTheSevenQualifiedProvidersAndNothingElse() {
        Set<String> expected = new HashSet<>();
        ScipIndexerCatalog.qualifiedM24Descriptors().forEach(descriptor -> expected.add(descriptor.id()));
        Set<String> actual = new HashSet<>();
        catalog.providers().forEach(provider -> actual.add(provider.id()));
        assertEquals(expected, actual);
    }

    @Test
    void everyComponentAProviderNeedsIsDescribed() {
        for (EmbeddedToolsCatalog.ProviderTools provider : catalog.providers()) {
            for (String component : provider.components()) {
                boolean pinned = catalog.artifacts().stream().anyMatch(artifact -> artifact.id().equals(component));
                boolean assembled = catalog.assembledComponents().stream().anyMatch(item -> item.id().equals(component));
                assertTrue(pinned || assembled, provider.id() + " needs the undescribed component " + component);
            }
        }
    }

    @Test
    void pinnedArtifactsAreHttpsWithAFullSha256AndEmbeddedOnesNameAUniquePayload() {
        Set<String> payloads = new HashSet<>();
        for (EmbeddedToolsCatalog.Artifact artifact : catalog.artifacts()) {
            assertTrue(artifact.url().startsWith("https://"), artifact.id());
            assertEquals(64, artifact.sha256().length(), artifact.id());
            if (artifact.embedded()) {
                assertTrue(payloads.add(artifact.payload()), "payload named twice: " + artifact.payload());
            }
        }
        for (EmbeddedToolsCatalog.Assembled component : catalog.assembledComponents()) {
            if (component.embedded()) {
                assertTrue(payloads.add(component.payload()), "payload named twice: " + component.payload());
            }
        }
    }

    @Test
    void theWindowsDistributionCarriesTheToolsTheManagersExpectToFind() {
        List<String> windowsPayload = catalog.artifacts().stream()
                .filter(artifact -> artifact.embedded()
                        && (artifact.platform().equals(EmbeddedToolsCatalog.PLATFORM_WINDOWS_X64)
                        || artifact.platform().equals(EmbeddedToolsCatalog.PLATFORM_ANY)))
                .map(EmbeddedToolsCatalog.Artifact::id)
                .sorted().toList();
        assertEquals(List.of("coursier", "maven", "nodejs"), windowsPayload);
        assertTrue(catalog.findAssembled("scip-typescript-modules", EmbeddedToolsCatalog.PLATFORM_WINDOWS_X64)
                .orElseThrow().embedded());
        assertTrue(catalog.findAssembled("scip-java-classpath", EmbeddedToolsCatalog.PLATFORM_WINDOWS_X64)
                .orElseThrow().embedded());
    }

    @Test
    void anUnknownArtifactIsAnErrorNotAnEmptyResult() {
        assertThrows(IllegalArgumentException.class, () -> catalog.artifact("no-such-tool", "windows-x64"));
        assertFalse(catalog.findArtifact("no-such-tool", "windows-x64").isPresent());
    }
}
