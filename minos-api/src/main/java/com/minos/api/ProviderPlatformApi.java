package com.minos.api;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Provider/discovery diagnostics API kept separate from MinosApi contract v1. */
public interface ProviderPlatformApi {
    String CONTRACT_VERSION = "1";

    default String contractVersion() { return CONTRACT_VERSION; }

    List<ProviderDto> listProviders() throws MinosApi.MinosApiException;

    ProviderDto getProvider(String providerId) throws MinosApi.MinosApiException;

    record ProviderDto(
            String id,
            String version,
            List<String> languages,
            List<String> buildSystems,
            Map<String, String> capabilities,
            int conformanceScorePercent,
            List<String> limitations,
            String runtimeState,
            List<String> runtimeDiagnostics
    ) {
        public ProviderDto {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("id must not be blank");
            if (version == null || version.isBlank()) throw new IllegalArgumentException("version must not be blank");
            languages = List.copyOf(Objects.requireNonNull(languages, "languages"));
            buildSystems = List.copyOf(Objects.requireNonNull(buildSystems, "buildSystems"));
            capabilities = sortedCopy(Objects.requireNonNull(capabilities, "capabilities"));
            limitations = List.copyOf(Objects.requireNonNull(limitations, "limitations"));
            runtimeDiagnostics = List.copyOf(Objects.requireNonNull(runtimeDiagnostics, "runtimeDiagnostics"));
        }

        /**
         * Unmodifiable copy iterated in the natural order of its keys, so the JSON built from it has a stable key
         * order on every JVM; like {@code Map.copyOf}, it refuses a null key and a null value.
         */
        private static Map<String, String> sortedCopy(Map<String, String> source) {
            Map<String, String> copy = new TreeMap<>();
            source.forEach((key, value) -> copy.put(
                    Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value")));
            return Collections.unmodifiableMap(copy);
        }
    }
}
