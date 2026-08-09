package com.minos.runtime;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerSandboxBackendsTest {

    @Test
    void selectorNeverClaimsOsEnforcementWithoutAQualifiedBackend() throws Exception {
        Path home = Files.createTempDirectory("minos-worker-sandbox-selector-");
        WorkerSandboxBackend selected = WorkerSandboxBackends.strongestAvailable(home);
        if (selected.networkGuarantee() == WorkerSandboxBackend.NetworkGuarantee.OS_ENFORCED) {
            assertTrue(selected.qualification().sandboxClaimPermitted());
            assertTrue(selected.enforcesNetworkDeny());
            assertTrue(selected.qualification().qualifiedForCurrentPlatform());
        } else {
            assertEquals(WorkerSandboxBackend.NetworkGuarantee.NONE, selected.networkGuarantee());
            assertFalse(selected.enforcesNetworkDeny());
            assertFalse(selected.qualification().sandboxClaimPermitted());
        }
    }
}
