package com.minos.packaging;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class M29DockerNpmLockContractTest {
    @Test
    void releaseImageUsesRepositoryOwnedLockfilesAndNpmCi() throws Exception {
        Path root = Path.of(".").toAbsolutePath().normalize();
        String dockerfile = Files.readString(root.resolve("docker/Dockerfile.mcp.release"));
        String release = Files.readString(root.resolve("docker/scripts/prod-mcp-release.ps1"));

        assertTrue(dockerfile.contains("COPY scip-typescript-package-lock.json"));
        assertTrue(dockerfile.contains("COPY scip-python-package-lock.json"));
        assertTrue(dockerfile.contains("npm ci --prefix \"$ts_root\" --no-audit --no-fund --ignore-scripts"));
        assertTrue(dockerfile.contains("npm ci --prefix \"$py_root\" --no-audit --no-fund --ignore-scripts"));
        assertFalse(dockerfile.contains("npm install --prefix \"/opt/minos/provider-tools/scip-typescript"));
        assertFalse(dockerfile.contains("npm install --prefix \"/opt/minos/provider-tools/scip-python"));
        assertTrue(release.contains("scip-typescript-package-lock.json"));
        assertTrue(release.contains("scip-python-package-lock.json"));
    }
}
