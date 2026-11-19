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
    @Test
    void theDockerBackendIsDecidedFirstWithoutReadingTheTeamMode() throws Exception {
        Optional<String> refusal = ExplorerLaunchPolicy.refusal(RuntimeLocation.NATIVE, () -> true, () -> {
            throw new AssertionError("the team mode is not read");
        });

        assertEquals(Optional.of(ExplorerLaunchPolicy.DOCKER_BACKEND_REFUSED), refusal);
    }

    @Test
    void insideTheDockerRuntimeTheBackendIsNotRead() throws Exception {
        Optional<String> refusal = ExplorerLaunchPolicy.refusal(RuntimeLocation.DOCKER, () -> {
            throw new AssertionError("the backend switch is not read");
        }, () -> HostedMode.DISABLED);

        assertEquals(Optional.empty(), refusal);
    }

    @Test
    void theTeamModeIsDecidedOnAWorkstationHomeOnTheNativeBackend() throws Exception {
        assertEquals(Optional.of(ExplorerLaunchPolicy.TEAM_MODE_REFUSED),
                ExplorerLaunchPolicy.refusal(RuntimeLocation.NATIVE, () -> false, () -> HostedMode.ENABLED));
        assertEquals(Optional.empty(),
                ExplorerLaunchPolicy.refusal(RuntimeLocation.NATIVE, () -> false, () -> HostedMode.DISABLED));
    }

    @Test
    void anUnsupportedTeamModeIsRefusedWithItsOwnMessage() throws Exception {
        Optional<String> refusal = ExplorerLaunchPolicy.refusal(RuntimeLocation.NATIVE, () -> false, () -> {
            throw new IllegalArgumentException("unsupported hosted mode: maybe");
        });

        assertEquals(Optional.of("unsupported hosted mode: maybe"), refusal);
    }

    @Test
    void missingReadingsAreRefusedByName() {
        assertEquals("dockerBackend", assertThrows(NullPointerException.class,
                () -> ExplorerLaunchPolicy.refusal(RuntimeLocation.NATIVE, null, () -> HostedMode.DISABLED))
                .getMessage());
        assertEquals("teamMode", assertThrows(NullPointerException.class,
                () -> ExplorerLaunchPolicy.refusal(RuntimeLocation.NATIVE, () -> false, null)).getMessage());
    }
}
