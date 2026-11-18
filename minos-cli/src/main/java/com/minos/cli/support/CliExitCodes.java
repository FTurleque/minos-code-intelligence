package com.minos.cli.support;

/**
 * The stable exit codes of the CLI (ARCH-0016), defined once for every command. No command redeclares them, and
 * the exploration surface keeps the same names and values.
 */
public final class CliExitCodes {

    /** The command completed. */
    public static final int SUCCESS = 0;
    /** The command was valid but could not be carried out. */
    public static final int EXECUTION_ERROR = 1;
    /** The arguments or options were invalid. */
    public static final int USAGE_ERROR = 2;
    /**
     * Partial result: the output is valid for what could be read, but registry entries were skipped (counted and
     * shown). One meaning for every command, such as {@code project list} and the name resolution of
     * {@code inspect}, {@code project inspect} and {@code index-status}.
     */
    public static final int PARTIAL_RESULT = 3;

    private CliExitCodes() {
    }
}
