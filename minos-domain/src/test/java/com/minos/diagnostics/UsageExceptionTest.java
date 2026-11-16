package com.minos.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsageExceptionTest {

    @Test
    void aUsageErrorIsAnIllegalArgumentExceptionWithItsMessageAndNoCause() {
        UsageException failure = new UsageException("limit must be between 1 and 10");

        assertInstanceOf(IllegalArgumentException.class, failure);
        assertEquals("limit must be between 1 and 10", failure.getMessage());
        assertNull(failure.getCause());
    }

    @Test
    void anExistingCatchOfIllegalArgumentExceptionStillApplies() {
        IllegalArgumentException caught = assertThrows(IllegalArgumentException.class, () -> {
            throw new UsageException("unknown module");
        });

        assertTrue(caught instanceof UsageException);
    }

    @Test
    void aBlankMessageIsReplacedByTheDefaultOne() {
        assertEquals(UsageException.DEFAULT_MESSAGE, new UsageException(null).getMessage());
        assertEquals(UsageException.DEFAULT_MESSAGE, new UsageException("  ").getMessage());
    }

    @Test
    void theTypeIsNotFinalSoATypedUsageErrorCanExtendIt() {
        class UnknownThing extends UsageException {
            UnknownThing() {
                super("unknown thing");
            }
        }

        assertEquals("unknown thing", new UnknownThing().getMessage());
        assertTrue(!java.lang.reflect.Modifier.isFinal(UsageException.class.getModifiers()));
    }
}
