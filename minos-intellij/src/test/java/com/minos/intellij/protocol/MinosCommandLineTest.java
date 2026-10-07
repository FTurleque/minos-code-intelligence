package com.minos.intellij.protocol;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinosCommandLineTest {
    private static final String CMD = "C:\\Program Files\\MINOS\\minos.cmd";
    private static final String WINDOWS = "Windows 11";

    @Test
    void usesDirectArgumentsForNativeExecutables() {
        assertEquals(
                List.of("minos", "find-symbol", "project one", "Thing", "--format", "json"),
                MinosCommandLine.build(
                        "minos",
                        List.of("find-symbol", "project one", "Thing", "--format", "json"),
                        "Linux"));
    }

    @Test
    void routesWindowsCmdLaunchersThroughCmdExeWithDelayedExpansionDisabled() {
        List<String> command = MinosCommandLine.build(
                CMD,
                List.of("project", "add", "C:\\Work Space\\demo", "--format", "json"),
                WINDOWS);

        assertEquals(List.of("cmd.exe", "/d", "/v:off", "/s", "/c"), command.subList(0, 5));
        assertTrue(command.get(5).contains("\"C:\\Program Files\\MINOS\\minos.cmd\""));
        assertTrue(command.get(5).contains("\"C:\\Work Space\\demo\""));
    }

    @Test
    void rejectsControlCharactersInArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> MinosCommandLine.build("minos", List.of("bad\nargument"), "Linux"));
    }

    /** MINOS-AUD-C04 : sous cmd.exe, un jeton entre guillemets est littéral ; il n'est plus doublé ni échappé. */
    @Test
    void rendersABatchLauncherCommandWithoutAlteringTheArguments() {
        List<String> command = MinosCommandLine.build(CMD, List.of("simple", "a&b", "x y|<>^"), WINDOWS);

        assertEquals("\"" + CMD + "\" \"simple\" \"a&b\" \"x y|<>^\"", command.get(5));
    }

    @Test
    void refusesAnArgumentThatABatchLauncherCannotReceiveIntactAndNamesItWithTheWorkaround() {
        for (String argument : List.of("say \"hi\"", "%PATH%", "50% off")) {
            List<String> arguments = List.of("semantic-search", argument);
            IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                    () -> MinosCommandLine.build(CMD, arguments, WINDOWS), argument);

            assertTrue(refusal.getMessage().contains(argument), refusal.getMessage());
            assertTrue(refusal.getMessage().contains("minos.exe"), refusal.getMessage());
        }
    }

    @Test
    void refusesALauncherPathThatABatchLauncherCannotReceiveIntact() {
        List<String> arguments = List.of("project", "list");
        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> MinosCommandLine.build("C:\\100% Tools\\minos.cmd", arguments, WINDOWS));

        assertTrue(refusal.getMessage().startsWith("executable "), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("minos.exe"), refusal.getMessage());
    }

    @Test
    void aLongRefusedArgumentIsTruncatedInTheMessage() {
        String argument = "%" + "x".repeat(500);

        List<String> arguments = List.of(argument);
        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> MinosCommandLine.build(CMD, arguments, WINDOWS));

        assertFalse(refusal.getMessage().contains(argument));
        assertTrue(refusal.getMessage().length() < 400, refusal.getMessage());
    }

    @Test
    void stillRefusesControlCharactersForABatchLauncher() {
        for (String argument : List.of("bad\nargument", "bad\rargument", "bad\0argument")) {
            List<String> arguments = List.of(argument);
            assertThrows(IllegalArgumentException.class,
                    () -> MinosCommandLine.build(CMD, arguments, WINDOWS), argument);
        }
    }

    /** MINOS-AUD-C04 : un lanceur natif ne passe jamais par un interpréteur, donc aucune restriction de plus. */
    @Test
    void passesNativeWindowsAndLinuxLaunchersEveryArgumentUnchangedWithoutAnInterpreter() {
        List<String> arguments = List.of("say \"hi\"", "%PATH%", "50% off", "a&b");

        List<String> windows = MinosCommandLine.build("C:\\MINOS\\app\\minos.exe", arguments, WINDOWS);
        List<String> linux = MinosCommandLine.build("minos", arguments, "Linux");

        assertEquals(List.of("C:\\MINOS\\app\\minos.exe", "say \"hi\"", "%PATH%", "50% off", "a&b"), windows);
        assertEquals(List.of("minos", "say \"hi\"", "%PATH%", "50% off", "a&b"), linux);
        assertFalse(windows.contains("cmd.exe"));
    }

    @Test
    void aBatchLauncherIsLeftToAWindowsHostOnly() {
        List<String> linux = MinosCommandLine.build(CMD, List.of("say \"hi\"", "%PATH%"), "Linux");

        assertEquals(List.of(CMD, "say \"hi\"", "%PATH%"), linux);
    }

    @Test
    void recognizesExactlyTheCmdShapeItBuilds() {
        List<String> built = MinosCommandLine.build(CMD, List.of("project", "list"), WINDOWS);

        assertTrue(MinosCommandLine.isCmdBatchInvocation(built));
        assertFalse(MinosCommandLine.isCmdBatchInvocation(List.of("cmd.exe", "/d", "/s", "/c", "x")));
        assertFalse(MinosCommandLine.isCmdBatchInvocation(List.of("cmd.exe", "/d", "/v:off", "/s", "/c")));
        assertFalse(MinosCommandLine.isCmdBatchInvocation(
                List.of("cmd.exe", "/d", "/v:off", "/s", "/c", "x", "extra")));
        assertFalse(MinosCommandLine.isCmdBatchInvocation(
                List.of("C:\\Tools\\other.exe", "/d", "/v:off", "/s", "/c", "x")));
        assertFalse(MinosCommandLine.isCmdBatchInvocation(List.of("minos", "project", "list")));
    }
}
