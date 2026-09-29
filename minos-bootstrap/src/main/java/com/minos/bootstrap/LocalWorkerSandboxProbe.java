package com.minos.bootstrap;

import com.minos.runtime.local.WorkerSandboxBackend;
import com.minos.runtime.local.WorkerSandboxBackends;
import com.minos.runtime.WorkerSandboxProbe;
import com.minos.runtime.local.WorkerSandboxSelection;

import java.nio.file.Path;

/**
 * Implémentation de production du port : interroge les backends réels de l'hôte
 * ({@link WorkerSandboxBackends}), exactement comme la sonde que {@code minos doctor} portait
 * auparavant (DoctorCommand.probeWorkerSandbox) et la sélection de {@code remote index}.
 */
final class LocalWorkerSandboxProbe implements WorkerSandboxProbe {

    @Override
    public ManagedLocalSandbox managedLocalProvider(Path home) {
        WorkerSandboxBackend managedLocal = WorkerSandboxBackends.strongestAvailableForManagedLocalProvider(home);
        return new ManagedLocalSandbox(managedLocal.id(), managedLocal.supportsManagedLocalProvider());
    }

    @Override
    public UntrustedCodeSandbox untrustedCode(Path home) {
        return assess(WorkerSandboxBackends.selectForUntrustedCode(home));
    }

    /**
     * Traduit une sélection en données du port. Le rapport de refus n'est calculé que pour une
     * sélection refusée, comme le faisaient doctor et remote index.
     */
    static UntrustedCodeSandbox assess(WorkerSandboxSelection selection) {
        boolean available = selection.supportsUntrustedCode();
        return new UntrustedCodeSandbox(
                selection.backend().id(),
                available,
                selection.cause().name(),
                selection.rejectedBackendId(),
                selection.rejectionReasons(),
                available ? "" : selection.refusalReport());
    }
}
