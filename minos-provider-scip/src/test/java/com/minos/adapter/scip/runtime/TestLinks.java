package com.minos.adapter.scip.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** A directory link every platform can create without privilege: a junction on Windows, a symbolic link elsewhere. */
final class TestLinks {

    private TestLinks() {
    }

    static void directoryLink(Path link, Path target) throws IOException, InterruptedException {
        Files.createDirectories(target);
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            Process process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), target.toString())
                    .redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            if (process.waitFor() != 0) throw new IOException("mklink /J failed");
        } else {
            Files.createSymbolicLink(link, target);
        }
    }
}
