package com.minos.runtime.local.wsl;

import com.minos.runtime.local.process.ProviderProcessEnvironment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Builds the environment of every {@code wsl.exe} process the WSL route starts.
 *
 * <p>The environment is an allowlist of Windows process metadata, with {@code PATH} reduced to the system
 * directory and {@code WSLENV} set empty, so that no Windows variable is forwarded into the distribution.
 * The allowlisted values go through {@link ProviderProcessEnvironment#sanitize(Map, Map)}, which applies the
 * single secret pattern of MINOS on top of the list. Route settings (designated Maven repository, project
 * JDK) travel in the worker request, never through the Windows environment.</p>
 */
public final class WslCommandEnvironment {

    /** Variables copied from the parent process, in their canonical Windows spelling. */
    static final List<String> ALLOWED_KEYS = List.of(
            "SystemRoot", "WINDIR", "TEMP", "TMP", "USERPROFILE", "LOCALAPPDATA");

    private static final String SYSTEM_ROOT = "SystemRoot";

    private WslCommandEnvironment() {
    }

    /** Returns the environment of a {@code wsl.exe} command, built from {@code inherited}. */
    public static Map<String, String> build(Map<String, String> inherited) {
        Objects.requireNonNull(inherited, "inherited");
        LinkedHashMap<String, String> allowed = new LinkedHashMap<>();
        for (String key : ALLOWED_KEYS) {
            lookup(inherited, key).ifPresent(value -> allowed.put(key, value));
        }
        lookup(inherited, SYSTEM_ROOT)
                .filter(root -> !root.isBlank())
                .ifPresent(root -> allowed.put("PATH", systemDirectory(root)));
        allowed.put("WSLENV", "");
        return ProviderProcessEnvironment.sanitize(Map.of(), allowed);
    }

    /** Replaces the whole environment of {@code builder} by the route environment. */
    public static void apply(ProcessBuilder builder) {
        Objects.requireNonNull(builder, "builder");
        Map<String, String> environment = builder.environment();
        Map<String, String> route = build(Map.copyOf(environment));
        environment.clear();
        environment.putAll(route);
    }

    /** Windows variable names ignore case; a plain map from a test or a copy does not. */
    static Optional<String> lookup(Map<String, String> environment, String name) {
        String exact = environment.get(name);
        if (exact != null) {
            return Optional.of(exact);
        }
        return environment.entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getValue() != null
                        && entry.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    private static String systemDirectory(String systemRoot) {
        String root = systemRoot;
        while (root.endsWith("\\") || root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        return root + "\\System32";
    }
}
