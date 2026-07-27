package com.minos.intellij.protocol;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinosCommandLineTest {

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
    void routesWindowsCmdLaunchersThroughCmdExeWithQuoting() {
        List<String> command = MinosCommandLine.build(
                "C:\\Program Files\\MINOS\\minos.cmd",
                List.of("project", "add", "C:\\Work Space\\demo", "--format", "json"),
                "Windows 11");

        assertEquals(List.of("cmd.exe", "/d", "/s", "/c"), command.subList(0, 4));
        assertTrue(command.get(4).contains("\"C:\\Program Files\\MINOS\\minos.cmd\""));
        assertTrue(command.get(4).contains("\"C:\\Work Space\\demo\""));
    }

    @Test
    void rejectsControlCharactersInArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> MinosCommandLine.build("minos", List.of("bad\nargument"), "Linux"));
    }
}
