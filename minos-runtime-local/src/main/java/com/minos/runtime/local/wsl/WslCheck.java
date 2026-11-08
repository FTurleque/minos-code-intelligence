package com.minos.runtime.local.wsl;

import java.util.List;
import java.util.Objects;

/**
 * Result of one WSL route prerequisite check.
 *
 * @param id     identifier from the {@code doctor} contract (see {@link #IDENTIFIERS})
 * @param state  observed state
 * @param action what the user is expected to do; empty when {@code state} is {@link WslCheckState#OK}.
 *               It never contains an absolute path.
 */
public record WslCheck(String id, WslCheckState state, String action) {

    public static final String WSL_VERSION = "wsl-version";
    public static final String SYSTEMD = "systemd";
    public static final String CGROUP_DELEGATION = "cgroup-delegation";
    public static final String BUBBLEWRAP = "bubblewrap";
    public static final String MINOS_JVM = "minos-jvm";
    public static final String MAVEN = "maven";
    public static final String PROJECT_JDK = "project-jdk";
    public static final String MAVEN_REPOSITORY = "maven-repository";

    /** Every identifier, in the order the probe reports them. */
    public static final List<String> IDENTIFIERS = List.of(
            WSL_VERSION, SYSTEMD, CGROUP_DELEGATION, BUBBLEWRAP, MINOS_JVM, MAVEN, PROJECT_JDK, MAVEN_REPOSITORY);

    public WslCheck {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(action, "action");
        if (!IDENTIFIERS.contains(id)) {
            throw new IllegalArgumentException("unknown WSL check identifier: " + id);
        }
        if (state == WslCheckState.OK && !action.isEmpty()) {
            throw new IllegalArgumentException("a satisfied check carries no action");
        }
        if (state != WslCheckState.OK && action.isBlank()) {
            throw new IllegalArgumentException("an unsatisfied check must name the expected action");
        }
    }

    static WslCheck ok(String id) {
        return new WslCheck(id, WslCheckState.OK, "");
    }

    static WslCheck missing(String id, String action) {
        return new WslCheck(id, WslCheckState.MISSING, action);
    }

    static WslCheck unknown(String id, String action) {
        return new WslCheck(id, WslCheckState.UNKNOWN, action);
    }
}
