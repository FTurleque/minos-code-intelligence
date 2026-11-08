package com.minos.application;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What the host says about the prerequisites of the WSL2 route for Java indexing, for a diagnostic: the targeted
 * distribution and one check per prerequisite, identifiers and actions only, never an absolute path.
 *
 * @param distribution the WSL distribution the probe targeted, empty when none could be resolved
 * @param checks       one check per prerequisite, in the order the probe reports them
 */
public record WslJavaDiagnosis(Optional<String> distribution, List<Check> checks) {

    public WslJavaDiagnosis {
        Objects.requireNonNull(distribution, "distribution");
        checks = List.copyOf(Objects.requireNonNull(checks, "checks"));
    }

    /** Observed state of one prerequisite. */
    public enum State { OK, MISSING, UNKNOWN }

    /**
     * One prerequisite of the route.
     *
     * @param id     prerequisite identifier of the {@code doctor} contract
     * @param state  observed state
     * @param action what the user is expected to do; empty when the state is {@link State#OK}
     */
    public record Check(String id, State state, String action) {
        public Check {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(action, "action");
        }
    }
}
