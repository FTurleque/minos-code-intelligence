package com.minos.io;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BoundedOutputStreamTest {

    @Test
    void acceptsPayloadExactlyAtLimit() throws Exception {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (BoundedOutputStream output = new BoundedOutputStream(sink, 3, "test")) {
            output.write(new byte[]{1, 2, 3});
            assertEquals(3L, output.writtenBytes());
        }
        assertArrayEquals(new byte[]{1, 2, 3}, sink.toByteArray());
    }

    @Test
    void refusesWriteThatWouldCrossLimitWithoutPartiallyWritingIt() throws Exception {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (BoundedOutputStream output = new BoundedOutputStream(sink, 3, "test")) {
            output.write(new byte[]{1, 2});
            assertThrows(IOException.class, () -> output.write(new byte[]{3, 4}));
            assertEquals(2L, output.writtenBytes());
        }
        assertArrayEquals(new byte[]{1, 2}, sink.toByteArray());
    }

    @Test
    void rejectsSingleByteBeyondLimit() throws Exception {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (BoundedOutputStream output = new BoundedOutputStream(sink, 1, "test")) {
            output.write(1);
            assertThrows(IOException.class, () -> output.write(2));
        }
        assertArrayEquals(new byte[]{1}, sink.toByteArray());
    }
}
