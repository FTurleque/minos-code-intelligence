package com.minos.hosted;

import com.minos.diagnostics.UsageException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

/** A retention value outside its bounds is an input to correct, not an internal failure. */
class HostedRetentionPolicyUsageTest {

    @Test
    void eachValueOutOfBoundsIsAUsageError() {
        assertThrows(UsageException.class, () -> new HostedRetentionPolicy(1, 30, 30));
        assertThrows(UsageException.class, () -> new HostedRetentionPolicy(1_000, 0, 30));
        assertThrows(UsageException.class, () -> new HostedRetentionPolicy(1_000, 30, 99_999));
    }
}
