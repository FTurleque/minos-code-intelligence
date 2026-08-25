package com.minos.mcp;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MinosMcpServerLifecycleTest {

    @Test
    void eofSignalCompletesWhenClientClosesStdin() throws Exception {
        MinosMcpServer.EofAwareInputStream input = new MinosMcpServer.EofAwareInputStream(
                new ByteArrayInputStream(new byte[]{1}));
        assertEquals(1, input.read());
        assertEquals(-1, input.read());
        input.awaitEnd();
    }

    @Test
    void oversizedInboundMessageFailsBeforeTransportCanAccumulateIt() throws Exception {
        byte[] bytes = new byte[MinosMcpServer.MAX_INBOUND_MESSAGE_BYTES + 1];
        Arrays.fill(bytes, (byte) 'x');
        try (MinosMcpServer.EofAwareInputStream input = new MinosMcpServer.EofAwareInputStream(
                new ByteArrayInputStream(bytes))) {
            byte[] buffer = new byte[8192];
            assertThrows(IOException.class, () -> {
                while (input.read(buffer) >= 0) {
                    // Consume until the wrapper enforces the message cap.
                }
            });
        }
    }

    @Test
    void newlineResetsInboundMessageBudget() throws Exception {
        byte[] first = new byte[MinosMcpServer.MAX_INBOUND_MESSAGE_BYTES];
        byte[] second = new byte[MinosMcpServer.MAX_INBOUND_MESSAGE_BYTES];
        Arrays.fill(first, (byte) 'a');
        Arrays.fill(second, (byte) 'b');
        byte[] bytes = new byte[first.length + 1 + second.length + 1];
        System.arraycopy(first, 0, bytes, 0, first.length);
        bytes[first.length] = '\n';
        System.arraycopy(second, 0, bytes, first.length + 1, second.length);
        bytes[bytes.length - 1] = '\n';

        try (MinosMcpServer.EofAwareInputStream input = new MinosMcpServer.EofAwareInputStream(
                new ByteArrayInputStream(bytes))) {
            byte[] buffer = new byte[8192];
            int consumed = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) consumed += read;
            assertEquals(bytes.length, consumed);
            input.awaitEnd();
        }
    }
}
