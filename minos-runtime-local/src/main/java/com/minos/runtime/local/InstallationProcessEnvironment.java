package com.minos.runtime.local;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Builds the environment of a tool-installation or tool-probe command ({@code coursier}, {@code npm},
 * {@code dotnet}, {@code go}).
 *
 * <p>These commands run outside the sandbox and fetch code from the network, so they must not inherit
 * MINOS control-plane secrets. The base is the provider allowlist ({@link ProviderProcessEnvironment},
 * the single source of truth for what an external tool may receive); this class adds what an installer
 * needs on top of it (home directory on Linux, proxy and TLS trust, package caches) and then applies a
 * deny pattern that wins over every list, including values the caller declares.</p>
 */
public final class InstallationProcessEnvironment {

    /** Needed by npm, go and Coursier to find their configuration; absent from the provider allowlist off Windows. */
    private static final Set<String> HOME_KEYS = Set.of("HOME", "USER", "LOGNAME");

    /** Proxy, TLS trust and package caches, in the casings the tools read. */
    private static final Set<String> NETWORK_AND_CACHE_KEYS = Set.of(
            "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY", "ALL_PROXY",
            "http_proxy", "https_proxy", "no_proxy", "all_proxy",
            "SSL_CERT_FILE", "SSL_CERT_DIR",
            "XDG_CACHE_HOME", "XDG_CONFIG_HOME", "XDG_DATA_HOME",
            "npm_config_cache", "NUGET_PACKAGES", "DOTNET_CLI_HOME");

    /** Lower-case fragments of a name that mark a secret; compared case-insensitively. */
    private static final Set<String> SECRET_FRAGMENTS = Set.of("token", "secret", "password", "key", "credential");

    /** Name prefixes that are never forwarded, except the explicit exceptions below. */
    private static final Set<String> DENIED_PREFIXES = Set.of("minos_", "github_", "npm_");

    /** Not secrets, although they match a denied prefix. */
    private static final Set<String> DENIED_PREFIX_EXCEPTIONS = Set.of("npm_config_cache");

    private InstallationProcessEnvironment() {
    }

    /** Replaces the environment of {@code builder} by the installation environment plus {@code declared}. */
    public static void apply(ProcessBuilder builder, Map<String, String> declared) {
        Objects.requireNonNull(builder, "builder");
        Objects.requireNonNull(declared, "declared");
        Map<String, String> environment = builder.environment();
        Map<String, String> result = new LinkedHashMap<>(
                ProviderProcessEnvironment.sanitize(environment, Map.of()));
        environment.forEach((key, value) -> {
            if (key != null && value != null && isInstallerKey(key)) {
                result.put(key, value);
            }
        });
        result.putAll(declared);
        result.keySet().removeIf(InstallationProcessEnvironment::isDenied);
        environment.clear();
        environment.putAll(result);
    }

    private static boolean isInstallerKey(String candidate) {
        boolean windows = CommandLocator.isWindows();
        return matches(NETWORK_AND_CACHE_KEYS, candidate, windows)
                || (!windows && HOME_KEYS.contains(candidate));
    }

    private static boolean matches(Set<String> keys, String candidate, boolean ignoreCase) {
        return ignoreCase
                ? keys.stream().anyMatch(key -> key.equalsIgnoreCase(candidate))
                : keys.contains(candidate);
    }

    /** True for a name that looks like a secret or a MINOS/CI credential, whatever list it came from. */
    static boolean isDenied(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (DENIED_PREFIX_EXCEPTIONS.contains(lower)) {
            return false;
        }
        return DENIED_PREFIXES.stream().anyMatch(lower::startsWith)
                || SECRET_FRAGMENTS.stream().anyMatch(lower::contains);
    }
}
