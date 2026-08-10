package com.minos.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class LinuxManagedRuntimeScopeTest {

    @Test
    void managedRuntimeRootIsScopedToProviderAndVersion(@TempDir Path home) throws Exception {
        Path tools = home.resolve("tools").toAbsolutePath().normalize();
        Path selected = tools.resolve("scip-java/1.2.3/bin/scip-java");
        Path sibling = tools.resolve("scip-java/9.9.9/secret-marker");
        Files.createDirectories(selected.getParent());
        Files.createDirectories(sibling.getParent());
        Files.writeString(selected, "launcher");
        Files.writeString(sibling, "must-not-be-mounted");

        Path managed = LinuxBubblewrapWorkerSandboxBackend.managedRuntimeRoot(selected, tools);

        assertEquals(tools.resolve("scip-java/1.2.3"), managed);
        assertFalse(sibling.startsWith(managed));
    }

    @Test
    void incompleteManagedRuntimePathIsNotPromotedToBroadReadRoot(@TempDir Path home) {
        Path tools = home.resolve("tools").toAbsolutePath().normalize();
        assertNull(LinuxBubblewrapWorkerSandboxBackend.managedRuntimeRoot(tools, tools));
        assertNull(LinuxBubblewrapWorkerSandboxBackend.managedRuntimeRoot(
                tools.resolve("scip-java"), tools));
    }
}
