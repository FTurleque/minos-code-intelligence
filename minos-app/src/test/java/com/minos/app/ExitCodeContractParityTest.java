package com.minos.app;

import com.minos.cli.support.CliExitCodes;
import com.minos.explorer.ExplorerSession;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The exploration surface cannot depend on the CLI module, so the assembly, which sees both, proves that every exit
 * code {@link ExplorerSession} declares has the name and the value of a {@link CliExitCodes} constant.
 */
class ExitCodeContractParityTest {

    @Test
    void explorerExitCodesFollowTheCliContract() {
        Map<String, Integer> explorer = exitCodes(ExplorerSession.class);

        assertEquals(Map.of("SUCCESS", 0, "EXECUTION_ERROR", 1, "USAGE_ERROR", 2), explorer);
        assertEquals(List.of(), mismatches(explorer, exitCodes(CliExitCodes.class)));
    }

    @Test
    void divergentValueIsRefusedByName() {
        List<String> mismatches = mismatches(Map.of("SUCCESS", 0, "USAGE_ERROR", 2),
                Map.of("SUCCESS", 0, "USAGE_ERROR", 64));

        assertEquals(List.of("USAGE_ERROR: surface declares 2, CLI declares 64"), mismatches);
    }

    @Test
    void codeUnknownToTheCliIsRefusedByName() {
        List<String> mismatches = mismatches(Map.of("SUCCESS", 0, "TIMEOUT", 4), Map.of("SUCCESS", 0));

        assertEquals(List.of("TIMEOUT: surface declares 4, CLI declares no such code"), mismatches);
    }

    @Test
    void readsOnlyPublicStaticFinalIntConstants() {
        Map<String, Integer> codes = exitCodes(CliExitCodes.class);

        assertEquals(Map.of("SUCCESS", 0, "EXECUTION_ERROR", 1, "USAGE_ERROR", 2, "PARTIAL_RESULT", 3), codes);
        // String declares constants and fields, none of them a public static final int.
        assertTrue(exitCodes(String.class).isEmpty());
    }

    static List<String> mismatches(Map<String, Integer> surface, Map<String, Integer> cli) {
        List<String> mismatches = new ArrayList<>();
        new TreeMap<>(surface).forEach((name, value) -> {
            Integer expected = cli.get(name);
            if (expected == null) {
                mismatches.add(name + ": surface declares " + value + ", CLI declares no such code");
            } else if (!expected.equals(value)) {
                mismatches.add(name + ": surface declares " + value + ", CLI declares " + expected);
            }
        });
        return List.copyOf(mismatches);
    }

    static Map<String, Integer> exitCodes(Class<?> type) {
        Map<String, Integer> codes = new TreeMap<>();
        for (Field field : type.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (Modifier.isPublic(modifiers) && Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers)
                    && field.getType() == int.class) {
                try {
                    codes.put(field.getName(), field.getInt(null));
                } catch (IllegalAccessException exception) {
                    throw new AssertionError("cannot read " + type.getSimpleName() + "." + field.getName(),
                            exception);
                }
            }
        }
        return codes;
    }
}
