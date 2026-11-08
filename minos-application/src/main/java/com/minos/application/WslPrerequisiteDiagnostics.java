package com.minos.application;

/**
 * Port of the read-only probe of the WSL2 route prerequisites, offered by a composition root only on a host where the
 * route exists (Windows). It runs no provider and writes nothing.
 */
@FunctionalInterface
public interface WslPrerequisiteDiagnostics {

    /** Probes the default WSL distribution of this host. */
    WslJavaDiagnosis diagnose();
}
