package com.minos.runtime;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Fake hostile provider used by artifact-preservation regression tests.
 *
 * <p>Instead of writing a regular file at the generated-artifact path, it replaces that path with a
 * directory (optionally populated with a nested file, so cleanup must contend with a non-empty
 * directory). Optional trailing arguments let a test also exercise a non-zero exit and an indefinite
 * hang, so the same fixture proves artifact-location cleanup survives every provider outcome.</p>
 */
public final class DirectoryReplacingProviderMain {

    private DirectoryReplacingProviderMain() {
    }

    public static void main(String[] arguments) throws Exception {
        Path artifact = Path.of(arguments[0]);
        boolean populate = arguments.length < 2 || Boolean.parseBoolean(arguments[1]);
        Files.createDirectories(artifact);
        if (populate) {
            Files.writeString(artifact.resolve("nested.txt"), "hostile-content", StandardCharsets.UTF_8);
        }
        int exitCode = arguments.length > 2 ? Integer.parseInt(arguments[2]) : 0;
        boolean hang = arguments.length > 3 && Boolean.parseBoolean(arguments[3]);
        if (hang) {
            Thread.sleep(300_000L);
        }
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }
}
