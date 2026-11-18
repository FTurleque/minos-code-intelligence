package com.minos.explorer;

import com.minos.application.HostedMode;
import com.minos.domain.registry.ProjectPathMapping;

import java.util.Objects;
import java.util.Optional;

/**
 * Whether {@code minos explorer serve} may start on a home, and the single text of each refusal. The launch route
 * asks it before opening the application; the session asks it again on the opened application.
 */
public final class ExplorerLaunchPolicy {

    /** The refusal of a home whose host launcher runs on the Docker backend. */
    public static final String DOCKER_BACKEND_REFUSED = "this MINOS home runs on the Docker backend, whose data is"
            + " not opened on the host: start the optional explorer service of the Docker runtime instead (Compose"
            + " profile explorer), or switch the home to the native backend";
    /** The refusal of a home in team mode: the exploration surface has no per-project authorization. */
    public static final String TEAM_MODE_REFUSED =
            "minos explorer serve is not available when the team mode is enabled"
            + " (MINOS_HOSTED_MODE / minos.hosted.mode): the exploration surface has no per-project authorization."
            + " Disable the team mode for this MINOS_HOME to explore its semantic index on this machine";

    private ExplorerLaunchPolicy() {
    }

    /**
     * The refusal of the backend of the home, decided first: on a workstation, a home switched to the Docker backend
     * is not opened. Inside the Docker runtime the switch does not apply.
     */
    public static Optional<String> backendRefusal(ProjectPathMapping.RuntimeLocation location, boolean dockerBackend) {
        Objects.requireNonNull(location, "location");
        return location == ProjectPathMapping.RuntimeLocation.NATIVE && dockerBackend
                ? Optional.of(DOCKER_BACKEND_REFUSED) : Optional.empty();
    }

    /** The refusal of the team mode of the home. */
    public static Optional<String> teamModeRefusal(HostedMode mode) {
        return Objects.requireNonNull(mode, "mode") == HostedMode.ENABLED
                ? Optional.of(TEAM_MODE_REFUSED) : Optional.empty();
    }
}
