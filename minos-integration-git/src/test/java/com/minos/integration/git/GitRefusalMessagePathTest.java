package com.minos.integration.git;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** A project root that cannot be opened is refused by its name, never by its absolute path (AUD-SEC-04). */
class GitRefusalMessagePathTest {

    @TempDir
    Path temp;

    @Test
    void aProjectRootThatIsAFileIsRefusedByItsName() throws Exception {
        Path file = Files.writeString(temp.resolve("not-a-repository.txt"), "x");

        GitIntelligenceService service = new GitIntelligenceService();

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> service.inspect(file));

        assertEquals("projectRoot must be an existing directory: not-a-repository.txt", refusal.getMessage());
        assertFalse(refusal.getMessage().contains(temp.toString()), refusal.getMessage());
    }
}
