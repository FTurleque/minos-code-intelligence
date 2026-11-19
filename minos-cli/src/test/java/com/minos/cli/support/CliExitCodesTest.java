package com.minos.cli.support;

import com.minos.cli.command.FindSymbolCommand;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pins the stable CLI exit codes (ARCH-0016) and their single definition in {@link CliExitCodes}. */
class CliExitCodesTest {

    private static final Set<String> EXIT_CODE_NAMES =
            Set.of("SUCCESS", "EXECUTION_ERROR", "USAGE_ERROR", "PARTIAL_RESULT");

    @Test
    void exitCodesKeepTheirPublishedValues() {
        assertEquals(0, CliExitCodes.SUCCESS);
        assertEquals(1, CliExitCodes.EXECUTION_ERROR);
        assertEquals(2, CliExitCodes.USAGE_ERROR);
        assertEquals(3, CliExitCodes.PARTIAL_RESULT);
    }

    @Test
    void noCommandDeclaresAnExitCodeAlias() {
        List<String> aliases = Arrays.stream(FindSymbolCommand.class.getDeclaredFields())
                .map(Field::getName)
                .filter(EXIT_CODE_NAMES::contains)
                .sorted()
                .toList();

        assertEquals(List.of(), aliases, "FindSymbolCommand must not redeclare the CLI exit codes");
    }
}
