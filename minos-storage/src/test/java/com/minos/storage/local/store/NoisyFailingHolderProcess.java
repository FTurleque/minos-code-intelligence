package com.minos.storage.local.store;

/**
 * Subprocess entry point used by the cross-JVM lock holder tests: a holder that never signals READY,
 * writes far more to stderr than any pipe buffer holds, then exits with a known code.
 */
public final class NoisyFailingHolderProcess {

    static final int EXIT_CODE = 3;
    static final String FIRST_LINE = "FIRST-NOISE-LINE";
    static final String LAST_LINE = "LAST-NOISE-LINE";
    private static final int FILLER_LINES = 1024;

    private NoisyFailingHolderProcess() {
    }

    public static void main(String[] args) {
        System.err.println(FIRST_LINE);
        String filler = "x".repeat(1023);
        // About 1 MiB: a child whose stderr is not drained blocks long before the end.
        for (int line = 0; line < FILLER_LINES; line++) System.err.println(filler);
        System.err.println(LAST_LINE);
        System.err.flush();
        System.exit(EXIT_CODE);
    }
}
