package com.minos.program;

/** Stable edge categories used by advanced program analysis. */
public enum ProgramEdgeKind {
    CALL,
    CONTROL_FLOW,
    DEF_USE,
    DATA_FLOW,
    ARGUMENT_FLOW,
    RETURN_FLOW,
    TAINT_FLOW
}
