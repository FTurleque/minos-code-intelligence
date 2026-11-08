package com.minos.runtime.local.wsl;

import java.util.List;

/**
 * Third step of the WSL prerequisite probe: the MINOS jar, run inside the distribution in the probe mode of
 * the internal worker command, applies the existing Linux scip-java checks (Maven 3.9.x read on disk,
 * project JDK, designated Maven repository).
 *
 * <p>This module cannot call those checks itself ({@code minos-provider-scip} depends on it), hence this
 * extension point. Only the identifiers {@link WslCheck#MAVEN}, {@link WslCheck#PROJECT_JDK} and
 * {@link WslCheck#MAVEN_REPOSITORY} are read from the result; a missing identifier is reported
 * {@link WslCheckState#UNKNOWN}.</p>
 */
@FunctionalInterface
public interface WslJarProbe {

    /**
     * Used when no installation jar can run in the distribution (a run from class directories): the jar checks are
     * reported unknown. A packaged Windows installation uses {@link WslCommandJarProbe}.
     */
    WslJarProbe NOT_AVAILABLE = distribution -> List.of();

    /** Runs the jar-level checks in {@code distribution}, a validated WSL distribution name. */
    List<WslCheck> probe(String distribution);
}
