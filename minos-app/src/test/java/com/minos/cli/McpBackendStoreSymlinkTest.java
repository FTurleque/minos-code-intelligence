package com.minos.cli;

import com.minos.io.PrivateLocalStorage;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class McpBackendStoreSymlinkTest {

    @Test
    void symlinkedRuntimeDirectoryIsRejectedBeforeConfigurationMigration(@TempDir Path root) throws Exception {
        Path home = root.resolve("home");
        PrivateLocalStorage.ensurePrivateDirectory(home);
        Path outsideRuntime = root.resolve("outside-runtime");
        Files.createDirectories(outsideRuntime);
        Path runtime = home.resolve(McpBackendConfigurationStore.RUNTIME_DIRECTORY);
        try {
            Files.createSymbolicLink(runtime, outsideRuntime.toAbsolutePath());
        } catch (UnsupportedOperationException | SecurityException | IOException exception) {
            Assumptions.assumeTrue(false,
                    "symbolic links are unavailable: " + exception.getClass().getSimpleName());
        }

        McpBackendConfigurationStore store = new McpBackendConfigurationStore(home);
        assertThrows(IOException.class, store::loadOrMigrate);
        assertFalse(Files.exists(outsideRuntime.resolve(McpBackendConfigurationStore.FILE_NAME)),
                "migration must not write through a runtime-directory symlink");
    }
}
