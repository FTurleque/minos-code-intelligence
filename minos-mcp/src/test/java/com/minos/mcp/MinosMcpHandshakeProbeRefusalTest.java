package com.minos.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The probe refuses a missing launcher by its name, never by its absolute path, before creating anything. */
class MinosMcpHandshakeProbeRefusalTest {

    @TempDir
    Path temp;

    @Test
    void aMissingLauncherIsRefusedByItsNameBeforeMinosHomeIsCreated() {
        Path launcher = temp.resolve("missing-launcher");
        Path home = temp.resolve("home");

        String[] arguments = {launcher.toString(), home.toString()};

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class, () -> MinosMcpHandshakeProbe.main(arguments));

        assertEquals("MINOS launcher is missing: missing-launcher", refusal.getMessage());
        assertFalse(refusal.getMessage().contains(temp.toString()), refusal.getMessage());
        assertFalse(Files.exists(home), "a refused probe creates no MINOS_HOME");
    }

    @Test
    void aMinosHomeThatCannotBeCreatedIsRefusedByItsNameBeforeAnyHandshake() throws Exception {
        Path launcher = Files.writeString(temp.resolve("launcher"), "never executed");
        Path blocker = Files.writeString(temp.resolve("regular-file"), "x");
        Path home = blocker.resolve("home");

        String[] arguments = {launcher.toString(), home.toString()};

        IllegalStateException refusal = assertThrows(IllegalStateException.class, () -> MinosMcpHandshakeProbe.main(arguments));

        assertEquals("Unable to create MINOS_HOME: home", refusal.getMessage());
        assertFalse(refusal.getMessage().contains(temp.toString()), refusal.getMessage());
        assertFalse(Files.exists(home), "a refused probe creates no MINOS_HOME");
    }
}
