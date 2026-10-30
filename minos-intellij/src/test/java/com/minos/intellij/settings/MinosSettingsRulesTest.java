package com.minos.intellij.settings;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MinosSettingsRulesTest {

    @Test
    void theDefaultExecutableDependsOnTheSystem() {
        assertEquals("minos.cmd", MinosSettingsRules.defaultExecutable("Windows 11"));
        assertEquals("minos.cmd", MinosSettingsRules.defaultExecutable("WINDOWS SERVER 2022"));
        assertEquals("minos", MinosSettingsRules.defaultExecutable("Linux"));
        assertEquals("minos", MinosSettingsRules.defaultExecutable(null));
    }

    @Test
    void aBlankExecutableFallsBackToTheDefaultAndAGivenOneIsTrimmed() {
        assertEquals("minos", MinosSettingsRules.executable(null, "Linux"));
        assertEquals("minos.cmd", MinosSettingsRules.executable("   ", "Windows 10"));
        assertEquals("C:\\Tools\\minos.cmd", MinosSettingsRules.executable("  C:\\Tools\\minos.cmd ", "Windows 10"));
    }

    @Test
    void aTimeoutOutsideOneToThreeHundredSecondsFallsBackToTheDefault() {
        assertEquals(1, MinosSettingsRules.timeoutSeconds(1));
        assertEquals(300, MinosSettingsRules.timeoutSeconds(300));
        assertEquals(MinosSettingsState.DEFAULT_TIMEOUT_SECONDS, MinosSettingsRules.timeoutSeconds(0));
        assertEquals(MinosSettingsState.DEFAULT_TIMEOUT_SECONDS, MinosSettingsRules.timeoutSeconds(301));
    }

    @Test
    void aGraphSizeOutsideTenToFiveHundredNodesFallsBackToTheDefault() {
        assertEquals(10, MinosSettingsRules.maxGraphNodes(10));
        assertEquals(500, MinosSettingsRules.maxGraphNodes(500));
        assertEquals(MinosSettingsState.DEFAULT_MAX_GRAPH_NODES, MinosSettingsRules.maxGraphNodes(9));
        assertEquals(MinosSettingsState.DEFAULT_MAX_GRAPH_NODES, MinosSettingsRules.maxGraphNodes(501));
    }
}
