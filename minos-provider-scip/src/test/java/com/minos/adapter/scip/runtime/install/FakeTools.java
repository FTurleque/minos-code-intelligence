package com.minos.adapter.scip.runtime.install;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Fake external executables for the managers that resolve their tools from {@code PATH}. The script is a
 * {@code .cmd} batch file on Windows and a {@code /bin/sh} script elsewhere; both only use the shell
 * built-ins (plus {@code /bin/mkdir} on POSIX, because the child {@code PATH} holds nothing but the fake
 * directory).
 */
final class FakeTools {

    private FakeTools() {
    }

    static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** Writes the fake {@code name}; {@code windowsLines} and {@code posixLines} are the script bodies. */
    static Path write(Path directory, String name, List<String> windowsLines, List<String> posixLines)
            throws IOException {
        Files.createDirectories(directory);
        if (windows()) {
            Path script = directory.resolve(name + ".cmd");
            Files.writeString(script, "@echo off\r\n" + String.join("\r\n", windowsLines) + "\r\n",
                    StandardCharsets.UTF_8);
            return script;
        }
        Path script = directory.resolve(name);
        Files.writeString(script, "#!/bin/sh\n" + String.join("\n", posixLines) + "\n", StandardCharsets.UTF_8);
        if (!script.toFile().setExecutable(true)) {
            throw new IOException("cannot mark the fake tool executable: " + name);
        }
        return script;
    }

    /** A fake tool that does nothing and succeeds. */
    static Path succeeding(Path directory, String name) throws IOException {
        return write(directory, name, List.of("exit /b 0"), List.of("exit 0"));
    }

    /** A fake tool that prints one line and succeeds. */
    static Path printing(Path directory, String name, String line) throws IOException {
        return write(directory, name, List.of("echo " + line, "exit /b 0"), List.of("echo '" + line + "'", "exit 0"));
    }

    /** A fake tool that fails with {@code exitCode} after printing one line. */
    static Path failing(Path directory, String name, String line, int exitCode) throws IOException {
        return write(directory, name,
                List.of("echo " + line, "exit /b " + exitCode),
                List.of("echo '" + line + "'", "exit " + exitCode));
    }
}
