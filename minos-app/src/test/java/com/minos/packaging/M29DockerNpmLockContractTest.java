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
        // Copy-JarResourceEntry and the Install logic this test verifies live in mcp-lifecycle.ps1,
        // the portable core prod-mcp-release.ps1 delegates every action to.
        String release = Files.readString(root.resolve("docker/scripts/mcp-lifecycle.ps1"));

        assertTrue(dockerfile.contains("COPY scip-typescript-package-lock.json"));
        assertTrue(dockerfile.contains("COPY scip-python-package-lock.json"));
        assertTrue(dockerfile.contains("npm ci --prefix \"$ts_root\" --no-audit --no-fund --ignore-scripts"));
        assertTrue(dockerfile.contains("npm ci --prefix \"$py_root\" --no-audit --no-fund --ignore-scripts"));
        assertFalse(dockerfile.contains("npm install --prefix \"/opt/minos/provider-tools/scip-typescript"));
        assertFalse(dockerfile.contains("npm install --prefix \"/opt/minos/provider-tools/scip-python"));
        assertTrue(release.contains("scip-typescript-package-lock.json"));
        assertTrue(release.contains("scip-python-package-lock.json"));

        // The lockfiles live under a Maven module's src/main/resources tree, compiled INTO the
        // shaded jar and never itself shipped as loose files under an installed {app}. A
        // $RepoRoot-relative Copy-Item only ever resolves from a git checkout; from an installed
        // product it fails with "the system cannot find the path specified" the first time a user
        // selects the Docker MCP backend. Must extract the bytes from the jar's own classpath
        // (the one dependency this script already resolves correctly in both contexts) instead.
        assertFalse(release.contains("minos-provider-scip"),
                "the provider-complete Docker image build must not reference a Maven module's "
                        + "source tree by a $RepoRoot-relative path -- that tree does not exist in "
                        + "an installed distribution");
        assertTrue(release.contains("Copy-JarResourceEntry"),
                "npm lockfiles for the Docker build context must be extracted from the packaged "
                        + "jar's classpath, not copied from a checkout-relative filesystem path");
    }
}
