package com.minos.characterization;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * A2 — fichiers de référence de caractérisation, comparés octet pour octet.
 *
 * <p>Les références vivent sous {@code minos-app/src/test/resources/characterization/} (fin de ligne
 * LF imposée par {@code .gitattributes}). Elles ne sont (ré)écrites que sur demande explicite,
 * {@code -Dminos.characterization.write=true} ; sans cette propriété, une référence absente est
 * un échec et non une création silencieuse. Une référence qui doit changer pendant A2 est une
 * régression, pas une mise à jour.</p>
 */
final class Golden {
    static final String WRITE_PROPERTY = "minos.characterization.write";
    private static final Path DIRECTORY = Path.of("minos-app", "src", "test", "resources", "characterization");

    private Golden() {
    }

    static void assertMatches(String name, String actual) throws IOException {
        Path file = DIRECTORY.resolve(name);
        if (Boolean.getBoolean(WRITE_PROPERTY)) {
            Files.createDirectories(DIRECTORY);
            Files.writeString(file, actual, StandardCharsets.UTF_8);
            return;
        }
        if (!Files.isRegularFile(file)) {
            fail("characterization golden is missing: " + name + " (generate it once with -D" + WRITE_PROPERTY + "=true)");
        }
        String expected = Files.readString(file, StandardCharsets.UTF_8);
        if (!expected.equals(actual)) {
            fail("characterization output changed for " + name + "\n" + firstDifference(expected, actual));
        }
    }

    private static String firstDifference(String expected, String actual) {
        String[] left = expected.split("\n", -1);
        String[] right = actual.split("\n", -1);
        int lines = Math.max(left.length, right.length);
        for (int line = 0; line < lines; line++) {
            String l = line < left.length ? left[line] : "<absent>";
            String r = line < right.length ? right[line] : "<absent>";
            if (!l.equals(r)) {
                return "first difference at line " + (line + 1) + "\n  expected: " + l + "\n  actual:   " + r;
            }
        }
        return "outputs differ only by trailing bytes";
    }
}
