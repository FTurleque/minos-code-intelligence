package com.minos.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdeCommandTest {

    @Test
    void exposesStableJsonHandshakeWithoutApplicationState() throws IOException {
        StringBuilder output = new StringBuilder();
        StringBuilder error = new StringBuilder();

        int exit = new IdeCommand().run(new String[]{"handshake", "--format", "json"}, output, error);

        assertEquals(0, exit);
        assertEquals("", error.toString());
        assertTrue(output.toString().contains("\"protocol\":\"minos-ide\""));
        assertTrue(output.toString().contains("\"protocolVersion\":\"1\""));
        assertTrue(output.toString().contains("\"transport\":\"cli-json-process\""));
        assertTrue(output.toString().contains("\"git-activity\""));
    }

    @Test
    void defaultsHandshakeToJsonForExternalClients() throws IOException {
        StringBuilder output = new StringBuilder();

        int exit = new IdeCommand().run(new String[]{"handshake"}, output, new StringBuilder());

        assertEquals(0, exit);
        assertTrue(output.toString().startsWith("{\"protocol\":\"minos-ide\""));
    }
}
