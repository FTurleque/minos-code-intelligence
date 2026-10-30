package com.minos.diagnostics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** A message names an entry by its last name element, never by the absolute path that leads to it (AUD-SEC-04). */
class PublicErrorMessagesLeafNameTest {

    @TempDir
    Path temp;

    @Test
    void anAbsolutePathIsNamedByItsLastElementOnly() {
        String name = PublicErrorMessages.leafName(temp.resolve("registry").resolve("project.properties"));

        assertEquals("project.properties", name);
        assertFalse(name.contains(temp.toString()), name);
    }

    @Test
    void aRelativePathIsNamedByItsLastElementOnly() {
        assertEquals("secret.txt", PublicErrorMessages.leafName(Path.of("..", "outside", "secret.txt")));
    }

    @Test
    void aRootOrAMissingPathHasAFixedName() {
        assertEquals("<root>", PublicErrorMessages.leafName(temp.getRoot()));
        assertEquals("<none>", PublicErrorMessages.leafName(null));
    }
}
