package com.minos.discovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MINOS-AUD-D10 : un fichier d'ignore enregistré avec un BOM UTF-8 (Windows PowerShell 5.1, anciens éditeurs) perdait
 * sa première règle : U+FEFF restait collé au motif, que {@code String.strip()} ne retire pas. Git retire le BOM
 * sur toutes les plateformes. Les octets {@code EF BB BF} sont écrits explicitement.
 */
class ProjectIgnoreByteOrderMarkTest {

    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    @Test
    void theFirstRuleOfAGitignoreWithAByteOrderMarkStillApplies(@TempDir Path root) throws IOException {
        write(root.resolve(".gitignore"), "generated/\nout2/\n", true);

        ProjectIgnorePolicy policy = ProjectIgnorePolicy.load(root);

        assertTrue(policy.isIgnored(Path.of("generated/X.java"), false), "the first rule is the one that was lost");
        assertTrue(policy.isIgnored(Path.of("out2/Y.java"), false));
    }

    @Test
    void theFirstRuleOfAMinosignoreWithAByteOrderMarkStillApplies(@TempDir Path root) throws IOException {
        write(root.resolve(".minosignore"), "/volumes/\n*.cache\n", true);

        ProjectIgnorePolicy policy = ProjectIgnorePolicy.load(root);

        assertTrue(policy.isIgnored(Path.of("volumes/db/data"), false));
        assertTrue(policy.isIgnored(Path.of("a.cache"), false));
    }

    @Test
    void aByteOrderMarkBeforeANegationAndBeforeACommentKeepsTheirMeaning(@TempDir Path root) throws IOException {
        write(root.resolve(".gitignore"), "*.log\n!keep.log\n", false);
        write(root.resolve(".minosignore"), "# a comment that starts the file\ngenerated/\n", true);

        ProjectIgnorePolicy policy = ProjectIgnorePolicy.load(root);

        assertTrue(policy.isIgnored(Path.of("a.log"), false));
        assertFalse(policy.isIgnored(Path.of("keep.log"), false));
        assertTrue(policy.isIgnored(Path.of("generated/X.java"), false), "a comment first line must not swallow the rules");
    }

    @Test
    void aFileWithoutAByteOrderMarkIsReadAsBefore(@TempDir Path root) throws IOException {
        write(root.resolve(".gitignore"), "generated/\nout2/\n", false);

        ProjectIgnorePolicy policy = ProjectIgnorePolicy.load(root);

        assertTrue(policy.isIgnored(Path.of("generated/X.java"), false));
        assertTrue(policy.isIgnored(Path.of("out2/Y.java"), false));
        assertFalse(policy.isIgnored(Path.of("src/Z.java"), false));
    }

    @Test
    void aByteOrderMarkOnALaterLineIsNotRemoved(@TempDir Path root) throws IOException {
        // Only the start of the file is a byte order mark: on another line U+FEFF is part of the pattern.
        byte[] content = concat("first/\n".getBytes(StandardCharsets.UTF_8), BOM, "second/\n".getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve(".gitignore"), content);

        ProjectIgnorePolicy policy = ProjectIgnorePolicy.load(root);

        assertTrue(policy.isIgnored(Path.of("first/X.java"), false));
        assertFalse(policy.isIgnored(Path.of("second/X.java"), false), "the pattern keeps its U+FEFF and matches nothing");
    }

    private static void write(Path file, String text, boolean withByteOrderMark) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        Files.write(file, withByteOrderMark ? concat(BOM, body) : body);
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) length += part.length;
        byte[] value = new byte[length];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, value, offset, part.length);
            offset += part.length;
        }
        return value;
    }
}
