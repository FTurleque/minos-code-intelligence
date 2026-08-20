package com.minos.program.analysis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The security rule set the analysis reads and the file its fingerprint covers must be the same file.
 *
 * <p>{@code BoundedProperties} already opens the security config with {@code NOFOLLOW_LINKS} and
 * refuses a link, so the loader's policy was never in doubt. The two fingerprint readers around it
 * validated the pathname and then re-opened it following links, which meant a link could be
 * fingerprinted through to its target while the loader refused the very same file -- and, on the
 * same pathname, a replacement between the validation and the read went unnoticed. Both now apply
 * the loader's policy.</p>
 */
class JavaSecurityConfigConfinementTest {

    @Test
    void aSymlinkedSecurityConfigIsRefusedInsteadOfFingerprintedThroughItsTarget(
            @TempDir Path root,
            @TempDir Path outside
    ) throws IOException {
        Path target = outside.resolve("rules.properties");
        Files.writeString(target, "sources=a\nsinks=b\n", StandardCharsets.UTF_8);
        Path config = root.resolve(JavaSourceProgramGraphProvider.SECURITY_CONFIG);
        Files.createDirectories(config.getParent());
        assumeTrue(canCreateSymbolicLinks(root), "this platform/account cannot create symbolic links");
        Files.createSymbolicLink(config, target);

        IOException failure = assertThrows(IOException.class, () -> JavaSourceWorkspace.securityConfig(root));

        assertTrue(failure.getMessage().contains("not a regular file"), failure.getMessage());
    }

    @Test
    void aRegularSecurityConfigStillResolvesAndLoads(@TempDir Path root) throws IOException {
        Path config = root.resolve(JavaSourceProgramGraphProvider.SECURITY_CONFIG);
        Files.createDirectories(config.getParent());
        Files.writeString(config, "sources=java.lang.System#getenv\nsinks=java.lang.Runtime#exec\n",
                StandardCharsets.UTF_8);

        assertTrue(JavaSourceWorkspace.securityConfig(root).isPresent());

        JavaSecurityRules rules = JavaSecurityRules.load(root);
        assertTrue(rules.configured());
        assertEquals(1, rules.sources().size());
        assertEquals(1, rules.sinks().size());
    }

    @Test
    void anAbsentSecurityConfigStaysAbsent(@TempDir Path root) throws IOException {
        assertTrue(JavaSourceWorkspace.securityConfig(root).isEmpty());
    }

    private static boolean canCreateSymbolicLinks(Path root) {
        Path probe = root.resolve("minos-symlink-probe");
        try {
            Files.createSymbolicLink(probe, root);
            return true;
        } catch (IOException | UnsupportedOperationException unsupported) {
            return false;
        } finally {
            try {
                Files.deleteIfExists(probe);
            } catch (IOException ignored) {
                // Probe cleanup failure must not mask the capability answer.
            }
        }
    }
}
