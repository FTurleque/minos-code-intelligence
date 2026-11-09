package com.minos.runtime.local.wsl;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Outcome of the WSL prerequisite probe: the distribution it targeted, when one was resolved, and one check
 * per identifier of {@link WslCheck#IDENTIFIERS}, in that order.
 */
public final class WslProbeReport {

    private final String distribution;
    private final List<WslCheck> checks;

    WslProbeReport(String distribution, List<WslCheck> checks) {
        this.distribution = distribution;
        this.checks = List.copyOf(Objects.requireNonNull(checks, "checks"));
    }

    /** The WSL distribution the probe targeted, absent when none could be resolved. */
    public Optional<String> distribution() {
        return Optional.ofNullable(distribution);
    }

    /** One check per contract identifier, in contract order. */
    public List<WslCheck> checks() {
        return checks;
    }

    /** True only when every check is {@link WslCheckState#OK}: an unknown state is never ready. */
    public boolean ready() {
        return checks.stream().allMatch(check -> check.state() == WslCheckState.OK);
    }

    /** The first missing prerequisite, which names the refusal of a run. */
    public Optional<WslCheck> firstMissing() {
        return checks.stream().filter(check -> check.state() == WslCheckState.MISSING).findFirst();
    }

    /**
     * Why an indexing run must not start, as an actionable message without a path: the first missing check, or the
     * first host check (WSL, systemd, cgroup delegation, bubblewrap, JVM) that could not be observed. The jar-level
     * checks (Maven, project JDK, designated repository) may stay unknown here: the worker applies the same checks
     * in the distribution before it starts the provider, and refuses without running Maven.
     */
    public Optional<String> runRefusal() {
        Optional<WslCheck> blocking = firstMissing().or(() -> checks.stream()
                .filter(check -> check.state() == WslCheckState.UNKNOWN && !JAR_CHECKS.contains(check.id()))
                .findFirst());
        return blocking.map(check -> "WSL route prerequisite " + check.id() + " is "
                + check.state().name().toLowerCase(java.util.Locale.ROOT) + ": " + check.action());
    }

    private static final List<String> JAR_CHECKS =
            List.of(WslCheck.MAVEN, WslCheck.PROJECT_JDK, WslCheck.MAVEN_REPOSITORY);
}
