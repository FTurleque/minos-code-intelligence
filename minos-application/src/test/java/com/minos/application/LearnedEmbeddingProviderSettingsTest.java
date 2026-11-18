package com.minos.application;

import com.minos.application.semantic.EmbeddingProvider;
import com.minos.storage.MinosRuntimeSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The application reads and validates the semantic settings, then asks the composition root for the learned
 * provider (ADR 0087): an invalid setting is refused before the root is asked, and a provider the root does not
 * supply is refused without any fallback.
 */
@ResourceLock("java.lang.System.properties")
class LearnedEmbeddingProviderSettingsTest {

    @TempDir
    Path home;

    @Test
    void validSettingsReachTheCompositionRootWhichSuppliesTheProvider() throws Exception {
        EmbeddingProvider supplied = proxy(EmbeddingProvider.class);
        RecordingComposer composer = new RecordingComposer(Optional.of(supplied));
        MinosApplication.Builder builder = builder(composer);

        withSettings(Map.of(MinosApplication.SEMANTIC_PROVIDER_PROPERTY, "Local-Ollama",
                MinosApplication.SEMANTIC_MODEL_PROPERTY, " code-embed ",
                MinosApplication.SEMANTIC_DIMENSIONS_PROPERTY, "768",
                MinosApplication.SEMANTIC_ENDPOINT_PROPERTY, "http://127.0.0.1:11434/api/embed",
                MinosApplication.SEMANTIC_TIMEOUT_SECONDS_PROPERTY, "15"),
                settings -> MinosApplicationRuntimeConfiguration.apply(settings, builder));

        MinosApplicationComposer.EmbeddingProviderSettings asked = composer.requests.getFirst();
        assertEquals("ollama", asked.providerId());
        assertEquals("code-embed", asked.model());
        assertEquals(768, asked.dimensions());
        assertEquals(Optional.of(URI.create("http://127.0.0.1:11434/api/embed")), asked.endpoint());
        assertEquals(Optional.of(Duration.ofSeconds(15)), asked.timeout());
        assertSame(supplied, builder.embeddingProvider);
    }

    @Test
    void absentEndpointAndTimeoutAreLeftToTheCompositionRoot() throws Exception {
        RecordingComposer composer = new RecordingComposer(Optional.of(proxy(EmbeddingProvider.class)));

        withSettings(Map.of(MinosApplication.SEMANTIC_PROVIDER_PROPERTY, "ollama",
                MinosApplication.SEMANTIC_MODEL_PROPERTY, "code-embed",
                MinosApplication.SEMANTIC_DIMENSIONS_PROPERTY, "384"),
                settings -> MinosApplicationRuntimeConfiguration.apply(settings, builder(composer)));

        assertEquals(Optional.empty(), composer.requests.getFirst().endpoint());
        assertEquals(Optional.empty(), composer.requests.getFirst().timeout());
    }

    @Test
    void aProviderTheRootDoesNotSupplyIsRefusedWithoutFallback() {
        RecordingComposer composer = new RecordingComposer(Optional.empty());
        MinosApplication.Builder builder = builder(composer);

        Map<String, String> properties = Map.of(MinosApplication.SEMANTIC_PROVIDER_PROPERTY, "ollama",
                MinosApplication.SEMANTIC_MODEL_PROPERTY, "code-embed",
                MinosApplication.SEMANTIC_DIMENSIONS_PROPERTY, "384");

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class, () -> withSettings(
                properties, settings -> MinosApplicationRuntimeConfiguration.apply(settings, builder)));

        assertEquals("unsupported semantic provider: ollama", refusal.getMessage());
        assertEquals(1, composer.requests.size());
        assertEquals(null, builder.embeddingProvider);
    }

    @Test
    void anUnknownProviderIsRefusedWithoutAskingTheRoot() {
        RecordingComposer composer = new RecordingComposer(Optional.of(proxy(EmbeddingProvider.class)));

        Map<String, String> properties = Map.of(MinosApplication.SEMANTIC_PROVIDER_PROPERTY, "remote-embedder");
        MinosApplication.Builder builder = builder(composer);

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class, () -> withSettings(
                properties, settings -> MinosApplicationRuntimeConfiguration.apply(settings, builder)));

        assertEquals("unsupported semantic provider: remote-embedder", refusal.getMessage());
        assertTrue(composer.requests.isEmpty());
    }

    @Test
    void anInvalidSettingIsRefusedBeforeTheRootIsAsked() {
        RecordingComposer composer = new RecordingComposer(Optional.of(proxy(EmbeddingProvider.class)));

        Map<String, String> properties = Map.of(MinosApplication.SEMANTIC_PROVIDER_PROPERTY, "ollama",
                MinosApplication.SEMANTIC_MODEL_PROPERTY, "code-embed",
                MinosApplication.SEMANTIC_DIMENSIONS_PROPERTY, "zero");
        MinosApplication.Builder builder = builder(composer);

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class, () -> withSettings(
                properties, settings -> MinosApplicationRuntimeConfiguration.apply(settings, builder)));

        assertEquals("semantic dimensions must be a positive integer", refusal.getMessage());
        assertTrue(composer.requests.isEmpty());
    }

    @Test
    void theSettingsRecordRefusesNonPositiveDimensions() {
        assertThrows(IllegalArgumentException.class, () -> new MinosApplicationComposer.EmbeddingProviderSettings(
                "ollama", "model", 0, Optional.empty(), Optional.empty(),
                com.minos.domain.registry.ProjectPathMapping.RuntimeLocation.NATIVE));
    }

    private MinosApplication.Builder builder(RecordingComposer composer) {
        MinosApplication.Builder builder = MinosApplication.builder(home);
        builder.composer = composer.port();
        return builder;
    }

    @FunctionalInterface
    private interface SettingsAction {
        void run(MinosRuntimeSettings settings) throws Exception;
    }

    private void withSettings(Map<String, String> properties, SettingsAction action) throws Exception {
        Map<String, String> previous = new HashMap<>();
        properties.keySet().forEach(key -> previous.put(key, System.getProperty(key)));
        try {
            properties.forEach(System::setProperty);
            action.run(MinosRuntimeSettings.load(home));
        } finally {
            previous.forEach((key, value) -> {
                if (value == null) System.clearProperty(key); else System.setProperty(key, value);
            });
        }
    }

    /** A composition root port that records the learned provider requests and answers nothing else. */
    private static final class RecordingComposer {
        private final Optional<EmbeddingProvider> answer;
        private final List<MinosApplicationComposer.EmbeddingProviderSettings> requests = new ArrayList<>();

        RecordingComposer(Optional<EmbeddingProvider> answer) {
            this.answer = answer;
        }

        MinosApplicationComposer port() {
            return (MinosApplicationComposer) Proxy.newProxyInstance(MinosApplicationComposer.class.getClassLoader(),
                    new Class<?>[] {MinosApplicationComposer.class}, (self, method, arguments) -> {
                        if (method.getName().equals("embeddingProvider")) {
                            requests.add((MinosApplicationComposer.EmbeddingProviderSettings) arguments[0]);
                            return answer;
                        }
                        throw new UnsupportedOperationException("composer." + method.getName());
                    });
        }
    }

    private static <T> T proxy(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (self, method, arguments) -> {
                    throw new UnsupportedOperationException(method.getName());
                }));
    }
}
