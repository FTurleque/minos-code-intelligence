package com.minos.adapter.scip.runtime.install;

import com.minos.runtime.local.process.CommandLocator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * La disponibilité de scip-java sous Windows juge le même PowerShell que celui qui lancera le runner : celui du
 * répertoire système, jamais un PowerShell trouvé dans le {@code PATH}.
 */
@EnabledOnOs(OS.WINDOWS)
class ScipJavaRuntimePowerShellTest {

    @Test
    void laDisponibiliteJugeLePowerShellDuRepertoireSysteme() {
        assertEquals(CommandLocator.windowsPowerShell(), ManagedScipProviderRuntimeManager.powerShellExecutable());
    }
}
