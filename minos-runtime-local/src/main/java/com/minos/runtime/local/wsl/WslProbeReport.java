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
}
