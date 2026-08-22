package com.minos.application;

import com.minos.semantic.LocalHashEmbeddingProvider;
import com.minos.semantic.OllamaEmbeddingProvider;
import com.minos.storage.MinosRuntimeSettings;
import com.minos.store.EnvironmentHostedTenantKeyProvider;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

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
            String model = requiredSetting(
                    settings, MinosApplication.SEMANTIC_MODEL_PROPERTY, MinosApplication.SEMANTIC_MODEL_ENV);
            int dimensions = parsePositiveInt(requiredSetting(
                    settings,
                    MinosApplication.SEMANTIC_DIMENSIONS_PROPERTY,
                    MinosApplication.SEMANTIC_DIMENSIONS_ENV), "semantic dimensions");
            String endpointValue = setting(
                    settings, MinosApplication.SEMANTIC_ENDPOINT_PROPERTY, MinosApplication.SEMANTIC_ENDPOINT_ENV);
            URI endpoint = endpointValue == null || endpointValue.isBlank()
                    ? OllamaEmbeddingProvider.DEFAULT_ENDPOINT
                    : URI.create(endpointValue.trim());
            String timeoutValue = setting(
                    settings,
                    MinosApplication.SEMANTIC_TIMEOUT_SECONDS_PROPERTY,
                    MinosApplication.SEMANTIC_TIMEOUT_SECONDS_ENV);
            Duration timeout = timeoutValue == null || timeoutValue.isBlank()
                    ? OllamaEmbeddingProvider.DEFAULT_TIMEOUT
                    : Duration.ofSeconds(parsePositiveInt(timeoutValue, "semantic timeout seconds"));
            builder.embeddingProvider(new OllamaEmbeddingProvider(endpoint, model, dimensions, timeout));
            return;
        }
        if (!"disabled".equals(provider)) {
            throw new IllegalArgumentException("unsupported semantic provider: " + configured);
        }
    }

    private static void configureHostedMode(MinosRuntimeSettings settings, MinosApplication.Builder builder) {
        String hostedMode = setting(
                settings, MinosApplication.HOSTED_MODE_PROPERTY, MinosApplication.HOSTED_MODE_ENV);
        if (hostedMode == null || hostedMode.isBlank() || "disabled".equalsIgnoreCase(hostedMode)) return;
        if (!"enabled".equalsIgnoreCase(hostedMode)) {
            throw new IllegalArgumentException("unsupported hosted mode: " + hostedMode);
        }
        builder.hostedTenantKeyProvider(new EnvironmentHostedTenantKeyProvider());
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
