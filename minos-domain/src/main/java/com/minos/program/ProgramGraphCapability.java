package com.minos.program;

/** Explicit advanced-analysis capabilities. Missing capabilities are never inferred. */
public enum ProgramGraphCapability {
    CALL_GRAPH,
    CONTROL_FLOW,
    LOCAL_DATA_FLOW,
    INTERPROCEDURAL_DATA_FLOW,
    CPG,
    SECURITY_TAINT
}
