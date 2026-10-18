package com.minos.adapter.scip.runtime.plan;

import com.minos.runtime.local.process.CommandLocator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Le runner scip-java Windows est lancé par le Windows PowerShell du répertoire système, le seul en qui le bac à sable
 * AppContainer a confiance : un PowerShell 7 placé avant lui dans le {@code PATH} (ou seul dans le {@code PATH}) est
 * refusé par le bac à sable, car il vit sous {@code Program Files}.
 */
@EnabledOnOs(OS.WINDOWS)
class ScipJavaPowerShellTest {

    @Test
    void lePlanUtiliseLePowerShellDuRepertoireSystemeQuelQueSoitLePath() {
        var canonical = CommandLocator.windowsPowerShell();
        assertTrue(canonical.isPresent(), "Windows PowerShell 5.1 is part of every supported Windows");

        assertEquals(canonical, ScipJavaProcessPlanFactory.powerShellExecutable());
    }
}
