package com.minos.adapter.scip.runtime;

import java.util.Locale;
import java.util.Set;

/**
 * Whether the managed-tool installers may use the network.
 *
 * <p>{@code MINOS_TOOLS_OFFLINE=1} (or the {@code minos.tools.offline} system property) confines every
 * managed installer to the payload shipped with the distribution: a component that is not there fails at
 * once with its reason instead of waiting for a connection that cannot succeed.</p>
 */
final class ToolsNetworkPolicy {

    static final String ENVIRONMENT_VARIABLE = "MINOS_TOOLS_OFFLINE";
    static final String SYSTEM_PROPERTY = "minos.tools.offline";
    private static final Set<String> TRUE_VALUES = Set.of("1", "true", "yes", "on");

    private ToolsNetworkPolicy() {
    }

    static boolean offline() {
        String property = System.getProperty(SYSTEM_PROPERTY);
        String value = property != null && !property.isBlank() ? property : System.getenv(ENVIRONMENT_VARIABLE);
        return value != null && TRUE_VALUES.contains(value.trim().toLowerCase(Locale.ROOT));
    }

    /** Fails when the network is disabled; {@code tool} is a catalogue id, never a path. */
    static void requireOnline(String tool) {
        if (offline()) {
            throw new IllegalStateException("network access is disabled (" + ENVIRONMENT_VARIABLE
                    + ") and the distribution payload does not provide " + tool);
        }
    }
}
