package com.minos.bootstrap.semantic;

import com.minos.application.MinosApplication;
import com.minos.application.semantic.EmbeddingProvider;
import com.minos.application.semantic.OllamaEmbeddingProvider;
import com.minos.application.semantic.SemanticIndexService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class M23SemanticProviderConfigurationTest {

    @Test
    void nativeOpenCanSelectExplicitLoopbackLearnedProvider(@TempDir Path temp) throws Exception {
        Map<String, String> settings = Map.of(
                MinosApplication.SEMANTIC_PROVIDER_PROPERTY, "ollama",
                MinosApplication.SEMANTIC_MODEL_PROPERTY, "fixture-code-embed",
                MinosApplication.SEMANTIC_DIMENSIONS_PROPERTY, "768",
                MinosApplication.SEMANTIC_ENDPOINT_PROPERTY, "http://127.0.0.1:11434/api/embed",
                MinosApplication.SEMANTIC_TIMEOUT_SECONDS_PROPERTY, "15");
        withProperties(settings, () -> {
            MinosApplication application = MinosApplication.open(temp.resolve("home"));
            EmbeddingProvider provider = application.semanticIndexService().embeddingProvider().orElseThrow();
            OllamaEmbeddingProvider ollama = assertInstanceOf(OllamaEmbeddingProvider.class, provider);
            assertEquals("minos-local-ollama", ollama.id());
            assertEquals("fixture-code-embed", ollama.modelId());
            assertEquals(768, ollama.dimensions());
            assertEquals("127.0.0.1", ollama.endpoint().getHost());
        });
    }

    @Test
    void nativeOpenRejectsRemoteLearnedEndpoint(@TempDir Path temp) throws Exception {
        Map<String, String> settings = Map.of(
                MinosApplication.SEMANTIC_PROVIDER_PROPERTY, "ollama",
                MinosApplication.SEMANTIC_MODEL_PROPERTY, "fixture-code-embed",
                MinosApplication.SEMANTIC_DIMENSIONS_PROPERTY, "384",
                MinosApplication.SEMANTIC_ENDPOINT_PROPERTY, "https://example.com/api/embed");
        withProperties(settings, () -> assertThrows(
                IllegalArgumentException.class,
                () -> MinosApplication.open(temp.resolve("home"))));
    }

    private static Map<String, String> managedOllamaSettings(String runtimeLocation) {
        Map<String, String> settings = new LinkedHashMap<>(Map.of(
                MinosApplication.SEMANTIC_PROVIDER_PROPERTY, "ollama",
                MinosApplication.SEMANTIC_MODEL_PROPERTY, "fixture-code-embed",
                MinosApplication.SEMANTIC_DIMENSIONS_PROPERTY, "768",
                MinosApplication.SEMANTIC_ENDPOINT_PROPERTY, OllamaEmbeddingProvider.MANAGED_DOCKER_ENDPOINT.toString()));
        if (runtimeLocation != null) settings.put("minos.runtime.location", runtimeLocation);
        return settings;
    }

    @Test
    void dockerOpenKeepsTheManagedOllamaServiceUsable(@TempDir Path temp) throws Exception {
        withProperties(managedOllamaSettings("docker"), () -> {
            try (MinosApplication application = MinosApplication.open(temp.resolve("home"))) {
                EmbeddingProvider provider = application.semanticIndexService().embeddingProvider().orElseThrow();
                OllamaEmbeddingProvider ollama = assertInstanceOf(OllamaEmbeddingProvider.class, provider);
                assertEquals("minos-ollama", ollama.endpoint().getHost());
                assertFalse(provider.limitations().contains(
                        OllamaEmbeddingProvider.MANAGED_SERVICE_OUTSIDE_DOCKER_LIMITATION));
            }
        });
    }

    /** Un MINOS_HOME écrit pour Docker reste ouvrable par un lanceur natif ; seul l'usage sémantique est refusé. */
    @Test
    void nativeOpenOfADockerConfiguredHomeSucceedsAndReportsTheProviderUnusable(@TempDir Path temp) throws Exception {
        Path project = Files.createDirectory(temp.resolve("project"));
        withProperties(managedOllamaSettings(null), () -> {
            try (MinosApplication application = MinosApplication.open(temp.resolve("home"))) {
                assertTrue(application.projectRegistry().listProjects().isEmpty());

                application.projectRegistry().registerProject(project, "unusable-provider");
                SemanticIndexService.Status status = application.semanticIndexService().status("unusable-provider");
                assertTrue(status.limitations().contains(OllamaEmbeddingProvider.MANAGED_SERVICE_OUTSIDE_DOCKER_LIMITATION),
                        status.limitations().toString());
                IOException refusal = assertThrows(IOException.class, () -> application.semanticIndexService()
                        .embeddingProvider().orElseThrow().embed("query", "text"));
                assertTrue(refusal.getMessage().contains("MINOS_RUNTIME_LOCATION=docker"), refusal.getMessage());
            }
        });
    }

    @Test
    void openRefusesAnUnknownRuntimeLocationByName(@TempDir Path temp) throws Exception {
        withProperties(managedOllamaSettings("podman"), () -> {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> MinosApplication.open(temp.resolve("home")));
            assertTrue(failure.getMessage().contains("podman"), failure.getMessage());
        });
    }

    private static void withProperties(Map<String, String> settings, ThrowingRunnable action) throws Exception {
        Map<String, String> previous = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : settings.entrySet()) {
            previous.put(entry.getKey(), System.getProperty(entry.getKey()));
            System.setProperty(entry.getKey(), entry.getValue());
        }
        try {
            action.run();
        } finally {
            for (Map.Entry<String, String> entry : previous.entrySet()) {
                if (entry.getValue() == null) System.clearProperty(entry.getKey());
                else System.setProperty(entry.getKey(), entry.getValue());
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
