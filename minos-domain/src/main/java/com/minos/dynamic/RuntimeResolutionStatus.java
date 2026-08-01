package com.minos.dynamic;

/** Explicit result of matching one runtime reference to an immutable static snapshot. */
public enum RuntimeResolutionStatus {
    RESOLVED,
    UNRESOLVED,
    AMBIGUOUS
}
