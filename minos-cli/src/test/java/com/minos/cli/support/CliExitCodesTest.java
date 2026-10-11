package com.minos.cli.support;

import com.minos.cli.command.FindSymbolCommand;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CliExitCodesTest {

    @Test
    void exitCodesKeepTheirPublishedValues() {
        assertEquals(0, CliExitCodes.SUCCESS);
        assertEquals(1, CliExitCodes.EXECUTION_ERROR);
        assertEquals(2, CliExitCodes.USAGE_ERROR);
        assertEquals(3, CliExitCodes.PARTIAL_RESULT);
    }

    @Test
    void findSymbolCommandConstantsAreTheSameCodes() {
        assertEquals(CliExitCodes.SUCCESS, FindSymbolCommand.SUCCESS);
        assertEquals(CliExitCodes.EXECUTION_ERROR, FindSymbolCommand.EXECUTION_ERROR);
        assertEquals(CliExitCodes.USAGE_ERROR, FindSymbolCommand.USAGE_ERROR);
        assertEquals(CliExitCodes.PARTIAL_RESULT, FindSymbolCommand.PARTIAL_RESULT);
    }
}
