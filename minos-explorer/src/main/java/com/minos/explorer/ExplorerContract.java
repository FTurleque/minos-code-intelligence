package com.minos.explorer;

/** Version of the HTTP contract of the exploration surface: every API route lives under {@link #API_PREFIX}. */
final class ExplorerContract {

    /** Contract version; an incompatible change publishes a new prefix beside this one. */
    static final String VERSION = "1";

    /** Path prefix of every API route of this contract version. */
    static final String API_PREFIX = "/api/v" + VERSION + "/";

    private ExplorerContract() {
    }
}
