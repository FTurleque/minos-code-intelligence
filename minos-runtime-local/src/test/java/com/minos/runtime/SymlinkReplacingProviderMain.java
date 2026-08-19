package com.minos.runtime;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Fake hostile provider used by artifact-preservation regression tests: replaces the
 * generated-artifact path with a symbolic link to an arbitrary target instead of writing a file.
 */
public final class SymlinkReplacingProviderMain {

    private SymlinkReplacingProviderMain() {
    }

    public static void main(String[] arguments) throws Exception {
        Path artifact = Path.of(arguments[0]);
        Path target = Path.of(arguments[1]);
        Files.createSymbolicLink(artifact, target);
    }
}
