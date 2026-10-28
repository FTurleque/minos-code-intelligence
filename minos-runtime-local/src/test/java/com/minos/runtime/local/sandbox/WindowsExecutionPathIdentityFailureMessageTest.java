package com.minos.runtime.local.sandbox;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A helper that dies before printing anything must still say how it died.
 *
 * <p>Platform independent: the message is built from the exit status alone, so the proof also
 * runs where the helper itself is never launched.</p>
 */
class WindowsExecutionPathIdentityFailureMessageTest {

    @Test
    void aSilentHelperFailureNamesItsExitStatus() {
        // 0xC0000142 (STATUS_DLL_INIT_FAILED): the shape of a process that dies during its own
        // initialization, before any script output exists.
        String message = WindowsExecutionPathIdentityProvider.queryFailureMessage(0xC0000142, "");

        assertEquals("Windows filesystem identity query failed (exit -1073741502 = 0xC0000142): "
                + "<no helper output>", message);
    }

    @Test
    void aHelperErrorKeepsItsBoundedOutputAfterTheExitStatus() {
        String message = WindowsExecutionPathIdentityProvider.queryFailureMessage(
                1, "CreateFileW failed for MINOS path identity\r\n");

        assertEquals("Windows filesystem identity query failed (exit 1 = 0x00000001): "
                + "CreateFileW failed for MINOS path identity", message);
    }

    @Test
    void aVerboseHelperErrorStaysBounded() {
        String message = WindowsExecutionPathIdentityProvider.queryFailureMessage(1, "x".repeat(10_000));

        assertTrue(message.length() < 700, () -> "unbounded diagnostic: " + message.length());
        assertTrue(message.endsWith("…"), message);
    }
}
