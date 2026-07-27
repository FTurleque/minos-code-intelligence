package com.minos.program;

/** Stable node categories used by the M19 program graph. */
public enum ProgramNodeKind {
    SYMBOL,
    BASIC_BLOCK,
    VARIABLE,
    PARAMETER,
    RETURN_VALUE,
    SOURCE,
    SINK,
    SANITIZER
}
