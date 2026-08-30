package com.minos.intellij.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * {@code MinosCliClient} caches a verified handshake keyed by {@code configurationKey()}
 * ({@link #resolvedExecutableIdentity} plus the configured path and {@code MINOS_HOME}), so an IDE
 * session never re-verifies an executable it already handshook with unless that key changes.
 * These tests cover the identity component of that key: it must change when the binary a
 * previously verified path resolves to is replaced in place (e.g. a MINOS upgrade performed while
 * the IDE stays open), and stay stable otherwise, so a stale cached handshake is not silently
 * trusted for a different binary.
 */
class MinosCliClientTest {

    @Test
    void secondHandshakeIsRequiredAfterTheExecutableIsReplacedAtTheSamePath(@TempDir Path temporary) throws Exception {
        Path executable = temporary.resolve("minos");
        Files.writeString(executable, "handshake-1-binary");

        // Command 1: handshake performed against the original binary at this path.
        String firstHandshakeIdentity = MinosCliClient.resolvedExecutableIdentity(executable.toString());

        // Same path, same content: a second command must reuse the cached handshake.
        String sameBinaryIdentity = MinosCliClient.resolvedExecutableIdentity(executable.toString());
        assertEquals(firstHandshakeIdentity, sameBinaryIdentity);

        // The binary is replaced in place (upgrade) while the IDE keeps running; path is unchanged.
        Files.writeString(executable, "handshake-2-binary-after-upgrade");

        // Command 2 at the same configured path must now observe a different identity, forcing
        // ensureHandshake() to treat the cache as stale and perform a new handshake.
        String secondHandshakeIdentity = MinosCliClient.resolvedExecutableIdentity(executable.toString());
        assertNotEquals(firstHandshakeIdentity, secondHandshakeIdentity);
    }

    @Test
    void unresolvableExecutableDoesNotThrowFromTheCacheKeyPath(@TempDir Path temporary) {
        Path missing = temporary.resolve("no-such-minos-binary");

        assertEquals("unresolved", MinosCliClient.resolvedExecutableIdentity(missing.toString()));
    }
}
