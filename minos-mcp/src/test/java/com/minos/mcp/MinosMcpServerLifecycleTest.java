package com.minos.mcp;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MinosMcpServerLifecycleTest {

    @Test
    void eofSignalCompletesWhenClientClosesStdin() throws Exception {
        MinosMcpServer.EofAwareInputStream input = new MinosMcpServer.EofAwareInputStream(
                new ByteArrayInputStream(new byte[]{1}));
        assertEquals(1, input.read());
        assertEquals(-1, input.read());
        input.awaitEnd();
    }
}
