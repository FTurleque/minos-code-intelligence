package com.minos.adapter.scip.runtime;

/**
 * Marks a diagnostic as a prerequisite of the machine (the toolchain of the project being analysed, or a
 * component of the operating system), not a tool MINOS ships: the distribution carries MINOS' own indexers,
 * never the JDK, Git, Go, the .NET SDK, cargo or Python of the user's projects.
 */
final class ExternalPrerequisite {

    static final String PREFIX = "machine prerequisite (not shipped by MINOS): ";

    private ExternalPrerequisite() {
    }

    static String of(String diagnostic) {
        return PREFIX + diagnostic;
    }
}
