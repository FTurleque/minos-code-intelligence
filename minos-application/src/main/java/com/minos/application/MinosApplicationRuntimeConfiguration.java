package com.minos.application;

import com.minos.application.semantic.LocalHashEmbeddingProvider;
import com.minos.storage.MinosRuntimeSettings;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Applies one immutable runtime settings snapshot to a MINOS application builder. */
final class MinosApplicationRuntimeConfiguration {
    private MinosApplicationRuntimeConfiguration() {
    }

    static void apply(MinosRuntimeSettings settings, MinosApplication.Builder builder) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(builder, "builder");
        configureSemanticProvider(settings, builder);
        configureHostedMode(settings, builder);
    }

    private static void configureSemanticProvider(MinosRuntimeSettings settings, MinosApplication.Builder builder) {
        String configured = setting(
                settings, MinosApplication.SEMANTIC_PROVIDER_PROPERTY, MinosApplication.SEMANTIC_PROVIDER_ENV);
        String provider = configured == null || configured.isBlank()
                ? "disabled"
                : configured.trim().toLowerCase(Locale.ROOT);
        if ("local-hash".equals(provider)) {
            builder.embeddingProvider(new LocalHashEmbeddingProvider());
            return;
        }
        if ("ollama".equals(provider) || "local-ollama".equals(provider)) {
            MinosApplicationComposer.EmbeddingProviderSettings learned = learnedProviderSettings(settings);
            builder.embeddingProvider(builder.resolvedComposer().embeddingProvider(learned)
                    .orElseThrow(() -> new IllegalArgumentException("unsupported semantic provider: " + configured)));
            return;
        }
        if (!"disabled".equals(provider)) {
            throw new IllegalArgumentException("unsupported semantic provider: " + configured);
        }
    }

    /** Reads and validates the settings of the learned provider before the composition root is asked for it. */
    private static MinosApplicationComposer.EmbeddingProviderSettings learnedProviderSettings(
            MinosRuntimeSettings settings) {
        String model = requiredSetting(
                settings, MinosApplication.SEMANTIC_MODEL_PROPERTY, MinosApplication.SEMANTIC_MODEL_ENV);
        int dimensions = parsePositiveInt(requiredSetting(
                settings,
                MinosApplication.SEMANTIC_DIMENSIONS_PROPERTY,
                MinosApplication.SEMANTIC_DIMENSIONS_ENV), "semantic dimensions");
        String endpointValue = setting(
                settings, MinosApplication.SEMANTIC_ENDPOINT_PROPERTY, MinosApplication.SEMANTIC_ENDPOINT_ENV);
        Optional<URI> endpoint = endpointValue == null || endpointValue.isBlank()
                ? Optional.empty()
                : Optional.of(URI.create(endpointValue.trim()));
        String timeoutValue = setting(
                settings,
                MinosApplication.SEMANTIC_TIMEOUT_SECONDS_PROPERTY,
                MinosApplication.SEMANTIC_TIMEOUT_SECONDS_ENV);
        Optional<Duration> timeout = timeoutValue == null || timeoutValue.isBlank()
                ? Optional.empty()
                : Optional.of(Duration.ofSeconds(parsePositiveInt(timeoutValue, "semantic timeout seconds")));
        return new MinosApplicationComposer.EmbeddingProviderSettings(
                "ollama", model, dimensions, endpoint, timeout, settings.runtimeLocation());
    }

    private static void configureHostedMode(MinosRuntimeSettings settings, MinosApplication.Builder builder) {
        if (HostedMode.from(settings) != HostedMode.ENABLED) return;
        builder.hostedTenantKeyProvider(builder.resolvedComposer().environmentHostedTenantKeyProvider());
    }

    private static String setting(MinosRuntimeSettings settings, String property, String environment) {
        return settings.value(property, environment);
    }

    private static String requiredSetting(MinosRuntimeSettings settings, String property, String environment) {
        String value = setting(settings, property, environment);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "missing required semantic setting: " + property + " / " + environment);
        }
        return value.trim();
    }

    private static int parsePositiveInt(String value, String label) {
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < 1) throw new NumberFormatException("not positive");
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(label + " must be a positive integer", exception);
        }
    }
}
