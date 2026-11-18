package com.minos.intellij.settings;

import java.util.Locale;

/** The bounds and defaults of the plugin settings, without the platform (AUD-TST-05). */
final class MinosSettingsRules {

    private MinosSettingsRules() {
    }

    static String defaultExecutable(String osName) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        return os.contains("win") ? "minos.cmd" : "minos";
    }

    static String executable(String configured, String osName) {
        return configured == null || configured.isBlank() ? defaultExecutable(osName) : configured.trim();
    }

    static int timeoutSeconds(int configured) {
        return configured < 1 || configured > 300 ? MinosSettingsState.DEFAULT_TIMEOUT_SECONDS : configured;
    }

    static int maxGraphNodes(int configured) {
        return configured < 10 || configured > 500 ? MinosSettingsState.DEFAULT_MAX_GRAPH_NODES : configured;
    }

    /** The timeout typed in the settings screen; a value that is not a whole number, or out of bounds, falls back. */
    static int timeoutSeconds(String typed) {
        return timeoutSeconds(parsedOr(typed, MinosSettingsState.DEFAULT_TIMEOUT_SECONDS));
    }

    /** The graph size typed in the settings screen; a value that is not a whole number, or out of bounds, falls back. */
    static int maxGraphNodes(String typed) {
        return maxGraphNodes(parsedOr(typed, MinosSettingsState.DEFAULT_MAX_GRAPH_NODES));
    }

    /** {@code typed} read as a whole number, surrounding spaces ignored, or {@code fallback}. */
    static int parsedOr(String typed, int fallback) {
        try {
            return Integer.parseInt(typed.trim());
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }
}
