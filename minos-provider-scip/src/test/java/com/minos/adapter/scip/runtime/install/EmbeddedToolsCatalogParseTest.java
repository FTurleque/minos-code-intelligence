package com.minos.adapter.scip.runtime.install;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tools catalogue is what decides which bytes MINOS downloads and runs: a malformed or weakened entry (no
 * hash, plain HTTP, an embedded component without its payload) must be refused when it is read, not when it is used.
 */
class EmbeddedToolsCatalogParseTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SHA = "a".repeat(64);

    private static JsonNode json(String text) throws Exception {
        return JSON.readTree(text);
    }

    private static String artifact(String overrides) {
        return ("{\"id\":\"maven\",\"platform\":\"any\",\"version\":\"3.9.16\",\"format\":\"zip\","
                + "\"url\":\"https://repo.example/maven.zip\",\"sha256\":\"" + SHA + "\",\"sizeBytes\":10,"
                + "\"license\":\"Apache-2.0\"" + overrides + "}");
    }

    private static String catalog(String providers, String artifacts, String assembled) {
        return "{\"formatVersion\":1,\"providers\":" + providers + ",\"artifacts\":" + artifacts
                + ",\"assembled\":" + assembled + "}";
    }

    private static EmbeddedToolsCatalog parse(String text) throws Exception {
        return EmbeddedToolsCatalog.parse(json(text));
    }

    @Test
    void aCompleteCatalogueAnswersProvidersArtifactsAndAssembledComponents() throws Exception {
        EmbeddedToolsCatalog catalogue = parse(catalog(
                "[{\"id\":\"scip-java\",\"version\":\"0.13.1\",\"license\":\"Apache-2.0\",\"components\":[\"maven\",\"classpath\"]}]",
                "[" + artifact("") + "," + artifact(",\"embedded\":true,\"payload\":\"artifacts/maven.zip\"")
                        .replace("\"platform\":\"any\"", "\"platform\":\"windows-x64\"") + "]",
                "[{\"id\":\"classpath\",\"platform\":\"windows-x64\",\"provider\":\"scip-java\",\"version\":\"0.13.1\","
                        + "\"recipe\":\"coursier-fetch\",\"license\":\"Apache-2.0\",\"embedded\":true,\"payload\":\"assembled/cp.zip\"}]"));

        assertEquals(List.of("maven", "classpath"), catalogue.provider("scip-java").components());
        assertEquals(1, catalogue.providers().size());
        assertEquals(2, catalogue.artifacts().size());
        assertEquals(1, catalogue.assembledComponents().size());
        assertTrue(catalogue.artifact("maven", "windows-x64").embedded(), "the platform entry wins over the neutral one");
        assertFalse(catalogue.artifact("maven", "linux-x64").embedded(), "another platform falls back to the neutral entry");
        assertTrue(catalogue.findArtifact("node", "linux-x64").isEmpty());
        assertTrue(catalogue.findAssembled("classpath", "windows-x64").isPresent());
        assertTrue(catalogue.findAssembled("classpath", "linux-x64").isEmpty());
    }

    @Test
    void anUnknownProviderOrArtifactIsRefusedByName() throws Exception {
        EmbeddedToolsCatalog catalogue = parse(catalog("[]", "[]", "[]"));

        IllegalArgumentException provider = assertThrows(IllegalArgumentException.class, () -> catalogue.provider("scip-go"));
        IllegalArgumentException artifact = assertThrows(IllegalArgumentException.class,
                () -> catalogue.artifact("node", "linux-x64"));

        assertTrue(provider.getMessage().contains("scip-go"), provider.getMessage());
        assertTrue(artifact.getMessage().contains("node") && artifact.getMessage().contains("linux-x64"), artifact.getMessage());
    }

    @Test
    void anotherFormatVersionIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> parse("{\"formatVersion\":2,\"providers\":[],\"artifacts\":[],\"assembled\":[]}"));
        assertThrows(IllegalArgumentException.class, () -> parse("{\"providers\":[],\"artifacts\":[],\"assembled\":[]}"));
    }

    @Test
    void everySectionMustBeAnArrayAndEveryNamedFieldMustBeText() {
        for (String broken : List.of(
                "{\"formatVersion\":1,\"providers\":{},\"artifacts\":[],\"assembled\":[]}",
                "{\"formatVersion\":1,\"providers\":[],\"artifacts\":\"none\",\"assembled\":[]}",
                "{\"formatVersion\":1,\"providers\":[],\"artifacts\":[]}",
                catalog("[{\"id\":\"scip-java\",\"version\":\"1\",\"license\":\"MIT\"}]", "[]", "[]"),
                catalog("[{\"id\":7,\"version\":\"1\",\"license\":\"MIT\",\"components\":[]}]", "[]", "[]"),
                catalog("[]", "[" + artifact("").replace("\"version\":\"3.9.16\",", "") + "]", "[]"))) {
            assertThrows(IllegalArgumentException.class, () -> parse(broken), broken);
        }
    }

    @Test
    void anArtifactWithoutAStrongHashHttpsOrPayloadIsRefused() {
        for (String weakened : List.of(
                artifact("").replace(SHA, "A".repeat(64)),
                artifact("").replace(SHA, "abc"),
                artifact("").replace("https://", "http://"),
                artifact("").replace("\"sizeBytes\":10", "\"sizeBytes\":-1"),
                artifact(",\"embedded\":true"),
                artifact(",\"embedded\":true,\"payload\":\" \""),
                artifact("").replace("\"license\":\"Apache-2.0\"", "\"license\":\" \""))) {
            assertThrows(RuntimeException.class, () -> parse(catalog("[]", "[" + weakened + "]", "[]")), weakened);
        }
    }

    @Test
    void anEmbeddedAssembledComponentMustNameItsPayload() {
        String component = "{\"id\":\"classpath\",\"platform\":\"windows-x64\",\"provider\":\"scip-java\",\"version\":\"1\","
                + "\"recipe\":\"coursier-fetch\",\"license\":\"MIT\",\"embedded\":true}";

        assertThrows(IllegalArgumentException.class, () -> parse(catalog("[]", "[]", "[" + component + "]")));
        assertThrows(RuntimeException.class,
                () -> parse(catalog("[]", "[]", "[" + component.replace("\"recipe\":\"coursier-fetch\",", "") + "]")));
    }

    @Test
    void thePlatformCanBeNamedByASystemPropertyAndABlankOneIsIgnored() {
        String property = "minos.tools.platform";
        String previous = System.getProperty(property);
        try {
            System.setProperty(property, "  linux-x64  ");
            assertEquals("linux-x64", EmbeddedToolsCatalog.currentPlatform());
            System.setProperty(property, "   ");
            assertFalse(EmbeddedToolsCatalog.currentPlatform().isBlank(), "a blank override falls back to the detected platform");
        } finally {
            if (previous == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, previous);
            }
        }
    }

    @Test
    void thePackagedCatalogueIsItselfValid() {
        EmbeddedToolsCatalog packaged = EmbeddedToolsCatalog.load();

        assertFalse(packaged.providers().isEmpty());
        assertTrue(packaged.artifacts().stream().allMatch(artifact -> artifact.url().startsWith("https://")));
    }
}
