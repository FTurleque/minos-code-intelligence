package com.minos.runtime;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Fake compliant provider: writes exactly one small artifact and exits. */
public final class CompliantProviderMain {

    private CompliantProviderMain() {
    }

    public static void main(String[] arguments) throws Exception {
        Path artifact = Path.of(arguments[0]);
        Files.createDirectories(artifact.getParent());
        Files.writeString(artifact, "contained-artifact", StandardCharsets.UTF_8);
    }
}
