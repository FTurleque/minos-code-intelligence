package com.minos.explorer;

import com.minos.application.HostedMode;
import com.minos.domain.registry.ProjectPathMapping.RuntimeLocation;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Each launch refusal of the exploration surface is decided by one policy and has one text. */
class ExplorerLaunchPolicyTest {

    @Test
    void aWorkstationHomeOnTheDockerBackendIsRefused() {
        assertEquals(Optional.of(ExplorerLaunchPolicy.DOCKER_BACKEND_REFUSED),
                ExplorerLaunchPolicy.backendRefusal(RuntimeLocation.NATIVE, true));
        assertEquals(Optional.empty(), ExplorerLaunchPolicy.backendRefusal(RuntimeLocation.NATIVE, false));
    }

    @Test
    void theDockerBackendSwitchDoesNotApplyInsideTheDockerRuntime() {
        assertEquals(Optional.empty(), ExplorerLaunchPolicy.backendRefusal(RuntimeLocation.DOCKER, true));
    }

    @Test
    void onlyAnEnabledTeamModeIsRefused() {
        assertEquals(Optional.of(ExplorerLaunchPolicy.TEAM_MODE_REFUSED),
                ExplorerLaunchPolicy.teamModeRefusal(HostedMode.ENABLED));
        assertEquals(Optional.empty(), ExplorerLaunchPolicy.teamModeRefusal(HostedMode.DISABLED));
        assertTrue(ExplorerLaunchPolicy.TEAM_MODE_REFUSED.contains("MINOS_HOSTED_MODE"));
    }

    @Test
    void missingInputsAreRefusedByName() {
        assertEquals("location", assertThrows(NullPointerException.class,
                () -> ExplorerLaunchPolicy.backendRefusal(null, false)).getMessage());
        assertEquals("mode", assertThrows(NullPointerException.class,
                () -> ExplorerLaunchPolicy.teamModeRefusal(null)).getMessage());
    }
}
