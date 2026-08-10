package com.minos.io;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BoundedInputStreamTest {

    @Test
    void acceptsPayloadExactlyAtLimit() throws Exception {
        try (BoundedInputStream input = new BoundedInputStream(
                new ByteArrayInputStream(new byte[]{1, 2, 3}), 3, "test")) {
            assertArrayEquals(new byte[]{1, 2, 3}, input.readAllBytes());
        }
    }

    @Test
    void rejectsPayloadThatGrowsBeyondLimitWhileReading() {
        assertThrows(IOException.class, () -> {
            try (BoundedInputStream input = new BoundedInputStream(
                    new ByteArrayInputStream(new byte[]{1, 2, 3, 4}), 3, "test")) {
                input.readAllBytes();
            }
        });
    }
}
