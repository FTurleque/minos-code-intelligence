package com.minos.runtime.local.sandbox;

import com.minos.remote.DistributedIndexing.WorkerIsolation;

/**
 * A worker sandbox that launches the provider through an operating-system isolation mechanism
 * (bubblewrap on Linux, AppContainer on Windows) inside an ephemeral workspace.
 *
 * <p>Declaring {@link NetworkGuarantee#OS_ENFORCED} here is not enough to claim network denial or
 * untrusted-code support: each implementation still provides its own platform qualification, which
 * {@link WorkerSandboxBackend#enforcesNetworkDeny()} and
 * {@link WorkerSandboxBackend#supportsUntrustedCode()} check.</p>
 */
interface OsEnforcedWorkerSandboxBackend extends WorkerSandboxBackend {

    @Override
    default WorkerIsolation isolation() {
        return WorkerIsolation.PROCESS_EPHEMERAL_WORKSPACE;
    }

    @Override
    default NetworkGuarantee networkGuarantee() {
        return NetworkGuarantee.OS_ENFORCED;
    }

    @Override
    WorkerSandboxQualification qualification();
}
