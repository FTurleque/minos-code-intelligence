package com.minos.runtime.local;

import com.minos.runtime.local.process.ProcessIndexerExecutor;

import java.nio.file.Path;

/**
 * Builds the ownership-only executor (strong descendant ownership, no sandbox, no network policy), whose
 * constructor is package-private. Only the containment tests that qualify the ownership boundary by itself
 * use it; production builds the executor with a network policy.
 */
public final class OwnershipOnlyExecutorsForTests {

    private OwnershipOnlyExecutorsForTests() {
    }

    public static StrongProcessOwnershipIndexerExecutor ownershipOnly(ProcessIndexerExecutor delegate, Path home) {
        return new StrongProcessOwnershipIndexerExecutor(delegate, home);
    }
}
