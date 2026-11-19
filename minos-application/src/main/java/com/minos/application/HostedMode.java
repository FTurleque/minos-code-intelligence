package com.minos.application;

import com.minos.storage.MinosRuntimeSettings;

import java.util.Locale;
import java.util.Objects;

/**
 * The team mode of a MINOS home, read by one rule wherever it is decided: absent, empty or {@code disabled} is
 * disabled, {@code enabled} is enabled, whatever the case; any other value is refused.
 */
public enum HostedMode {
    DISABLED,
    ENABLED;

    /**
     * The team mode the settings of a home declare ({@link MinosApplication#HOSTED_MODE_PROPERTY} or
     * {@link MinosApplication#HOSTED_MODE_ENV}).
     *
     * @throws IllegalArgumentException when the value is neither {@code enabled} nor {@code disabled}, with the
     *         message {@code unsupported hosted mode: <value>}
     */
    public static HostedMode from(MinosRuntimeSettings settings) {
        String value = Objects.requireNonNull(settings, "settings")
                .value(MinosApplication.HOSTED_MODE_PROPERTY, MinosApplication.HOSTED_MODE_ENV);
        if (value == null || value.isBlank() || "disabled".equals(value.toLowerCase(Locale.ROOT))) {
            return DISABLED;
        }
        if ("enabled".equals(value.toLowerCase(Locale.ROOT))) {
            return ENABLED;
        }
        throw new IllegalArgumentException("unsupported hosted mode: " + value);
    }
}
