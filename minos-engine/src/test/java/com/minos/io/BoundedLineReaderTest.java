package com.minos.io;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BoundedLineReaderTest {
    @Test
    void readsCrLfLfAndFinalLineWithinBound() throws Exception {
        try (BoundedLineReader reader = new BoundedLineReader(new StringReader("alpha\r\nbeta\ngamma"), 5)) {
            assertEquals("alpha", reader.readLine());
            assertEquals("beta", reader.readLine());
            assertEquals("gamma", reader.readLine());
            assertNull(reader.readLine());
        }
    }

    @Test
    void failsBeforeMaterializingOversizedLine() throws Exception {
        try (BoundedLineReader reader = new BoundedLineReader(new StringReader("abcdef\n"), 5)) {
            assertThrows(IOException.class, reader::readLine);
        }
    }
}
