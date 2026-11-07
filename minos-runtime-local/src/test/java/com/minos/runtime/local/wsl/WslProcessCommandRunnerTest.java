package com.minos.runtime.local.wsl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises {@link ProcessWslCommandRunner} with a child JVM standing for {@code wsl.exe}. */
class WslProcessCommandRunnerTest {

    @TempDir
    Path temporary;

    private static String javaExecutable() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString();
    }

    private Path program(String name, String body) throws IOException {
        Path source = temporary.resolve(name + ".java");
        Files.writeString(source, "public class " + name + " { public static void main(String[] a) throws Exception {"
                + body + "} }");
        return source;
    }

    @Test
    void returnsExitCodeAndStdoutWithTheRouteEnvironment() throws IOException {
        Path source = program("Echo", """
                System.out.print("WSLENV=[" + System.getenv("WSLENV") + "]");
                System.out.print(" token=" + (System.getenv("GITHUB_TOKEN") != null || System.getenv("MINOS_TEAM_TOKEN") != null));
                System.exit(3);
                """);

        WslCommandResult result = new ProcessWslCommandRunner()
                .run(List.of(javaExecutable(), source.toString()), Duration.ofMinutes(1));

        assertEquals(3, result.exitCode());
        assertEquals("WSLENV=[] token=false", new String(result.stdout(), StandardCharsets.UTF_8));
    }

    @Test
    void timeoutDestroysTheProcessWithAFixedMessage() throws IOException {
        Path source = program("Sleeper", "Thread.sleep(600_000);");

        IOException failure = assertThrows(IOException.class, () -> new ProcessWslCommandRunner()
                .run(List.of(javaExecutable(), source.toString()), Duration.ofSeconds(1)));

        assertEquals("wsl.exe command timed out", failure.getMessage());
    }

    @Test
    void outputBeyondTheBoundIsRefused() throws IOException {
        Path source = program("Flood", """
                byte[] chunk = new byte[64 * 1024];
                for (int i = 0; i < 64; i++) { System.out.write(chunk); }
                System.out.flush();
                """);

        IOException failure = assertThrows(IOException.class, () -> new ProcessWslCommandRunner()
                .run(List.of(javaExecutable(), source.toString()), Duration.ofMinutes(1)));

        assertTrue(failure.getMessage().contains("exceeds"), failure.getMessage());
        assertFalse(failure.getMessage().contains(temporary.toString()));
    }

    @Test
    void relativeOrEmptyCommandsAreRefusedBeforeAnyLaunch() {
        ProcessWslCommandRunner runner = new ProcessWslCommandRunner();

        assertThrows(IllegalArgumentException.class, () -> runner.run(List.of("wsl.exe", "-l", "-v"), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> runner.run(List.of(), Duration.ofSeconds(1)));
    }

    @Test
    void resultCopiesItsBytes() {
        byte[] bytes = {1, 2};
        WslCommandResult result = new WslCommandResult(0, bytes);
        bytes[0] = 9;

        assertEquals(1, result.stdout()[0]);
        assertEquals(new WslCommandResult(0, new byte[] {1, 2}), result);
        assertEquals(new WslCommandResult(0, new byte[] {1, 2}).hashCode(), result.hashCode());
        assertTrue(result.toString().contains("stdoutBytes=2"));
    }
}
