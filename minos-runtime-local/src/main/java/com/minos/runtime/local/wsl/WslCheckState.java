package com.minos.runtime.local.wsl;

/** State of one WSL route prerequisite, as reported by {@code doctor} and checked before a run. */
public enum WslCheckState {
    /** The prerequisite was observed and satisfied. */
    OK,
    /** The prerequisite was observed and is absent: the route refuses to run. */
    MISSING,
    /** The prerequisite could not be observed; it is never treated as satisfied. */
    UNKNOWN
}
