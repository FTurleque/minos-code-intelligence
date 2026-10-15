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
}
