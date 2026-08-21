package com.minos.runtime;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerSandboxBackendsTest {

    @Test
    void strictSelectorNeverClaimsOsEnforcementWithoutHostileCodeQualification() throws Exception {
        Path home = Files.createTempDirectory("minos-worker-sandbox-selector-strict-");
        WorkerSandboxBackend selected = WorkerSandboxBackends.strongestAvailable(home);
        if (selected.networkGuarantee() == WorkerSandboxBackend.NetworkGuarantee.OS_ENFORCED) {
            assertTrue(selected.supportsUntrustedCode());
            assertTrue(selected.qualification().sandboxClaimPermitted());
            assertTrue(selected.enforcesNetworkDeny());
            assertTrue(selected.qualification().qualifiedForCurrentPlatform());
        } else {
            assertEquals(WorkerSandboxBackend.NetworkGuarantee.NONE, selected.networkGuarantee());
            assertFalse(selected.supportsUntrustedCode());
            assertFalse(selected.qualification().sandboxClaimPermitted());
        }
    }

    @Test
    void managedLocalSelectorDoesNotPromoteItsNarrowerContractToUntrustedCode() throws Exception {
        Path home = Files.createTempDirectory("minos-worker-sandbox-selector-local-");
        WorkerSandboxBackend selected = WorkerSandboxBackends.strongestAvailableForManagedLocalProvider(home);
        if (selected.networkGuarantee() == WorkerSandboxBackend.NetworkGuarantee.OS_ENFORCED) {
            assertTrue(selected.supportsManagedLocalProvider());
            assertTrue(selected.qualification().managedLocalProviderClaimPermitted());
            assertTrue(selected.enforcesNetworkDeny());
            // Current Linux/Windows backends use a supervised filesystem quota, so the strict
            // untrusted claim normally remains false. Do not require that forever: a future real
            // kernel quota may legitimately make both claims true.
            if (!selected.resourceContainment().hardFilesystemQuotaEnforced()) {
                assertFalse(selected.supportsUntrustedCode());
                assertFalse(selected.qualification().sandboxClaimPermitted());
            }
        } else {
            assertEquals(WorkerSandboxBackend.NetworkGuarantee.NONE, selected.networkGuarantee());
            assertFalse(selected.supportsManagedLocalProvider());
        }
    }
}
