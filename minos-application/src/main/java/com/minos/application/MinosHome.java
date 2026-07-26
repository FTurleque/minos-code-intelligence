package com.minos.application;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/** Shared resolution policy for the local MINOS storage home. */
public final class MinosHome {

    public static final String ENVIRONMENT_VARIABLE = "MINOS_HOME";
    public static final String SYSTEM_PROPERTY = "minos.home";

    private MinosHome() {
    }

    public static Path resolve(Map<String, String> environment, Properties properties) {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(properties, "properties");

        String property = properties.getProperty(SYSTEM_PROPERTY);
        if (property != null && !property.isBlank()) {
            return Path.of(property);
        }
        String environmentValue = environment.get(ENVIRONMENT_VARIABLE);
        if (environmentValue != null && !environmentValue.isBlank()) {
            return Path.of(environmentValue);
        }
        String userHome = properties.getProperty("user.home");
        if (userHome == null || userHome.isBlank()) {
            throw new IllegalStateException(
                    "neither minos.home, MINOS_HOME nor user.home defines a storage directory");
        }
        return Path.of(userHome).resolve(".minos");
    }
}
