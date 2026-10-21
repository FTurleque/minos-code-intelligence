package com.minos.orchestration;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** AUD-QUA-03: the failure type of the index state port keeps its message and, when there is one, its cause. */
class IndexStateStoreExceptionTest {

    @Test
    void aFailureWithoutCauseKeepsItsMessage() {
        IndexStateStoreException failure = new IndexStateStoreException("unreadable state");

        assertEquals("unreadable state", failure.getMessage());
        assertNull(failure.getCause());
    }

    @Test
    void aFailureWithCauseKeepsBoth() {
        IOException cause = new IOException("disk");
        IndexStateStoreException failure = new IndexStateStoreException("cannot write state", cause);

        assertEquals("cannot write state", failure.getMessage());
        assertSame(cause, failure.getCause());
    }
}
