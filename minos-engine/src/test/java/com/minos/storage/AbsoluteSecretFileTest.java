package com.minos.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * An absolute secret path is an explicit operator escape hatch for a mounted secret store: the file is
 * read from one no-follow open, a link is refused, and no failure -- an exception message ends up in a
 * log -- names the path of the secret.
 */
class AbsoluteSecretFileTest {

    private static StorageBackendConfiguration resolve(Path home, Path secret) throws IOException {
        Properties file = new Properties();
        file.setProperty(StorageBackendConfiguration.BACKEND_PROPERTY, "postgresql");
        file.setProperty(StorageBackendConfiguration.POSTGRES_PASSWORD_FILE_PROPERTY, secret.toString());
        return StorageBackendConfiguration.resolve(MinosRuntimeSettings.testing(home, file, Map.of(), new Properties()));
    }

    @Test
    void anAbsoluteSecretIsRead(@TempDir Path temp) throws Exception {
        Path secret = Files.writeString(temp.resolve("mounted.password"), "mounted-secret\n");

        assertEquals("mounted-secret", resolve(temp.resolve("home"), secret).postgresPassword());
    }

    @Test
    void aMissingAbsoluteSecretFailsWithoutNamingItsPath(@TempDir Path temp) {
        Path secret = temp.resolve("absent.password");

        IOException failure = assertThrows(IOException.class, () -> resolve(temp.resolve("home"), secret));

        assertFalse(failure.getMessage().contains(secret.toString()), "the message names the path: " + failure.getMessage());
        assertFalse(failure.getMessage().contains(temp.toString()));
    }

    @Test
    void anEmptyAbsoluteSecretFailsWithoutNamingItsPath(@TempDir Path temp) throws Exception {
        Path secret = Files.writeString(temp.resolve("empty.password"), "  \n");

        IOException failure = assertThrows(IOException.class, () -> resolve(temp.resolve("home"), secret));

        assertFalse(failure.getMessage().contains(secret.toString()), "the message names the path: " + failure.getMessage());
    }

    @Test
    void aLinkToAnAbsoluteSecretIsRefusedWithoutNamingAnyPath(@TempDir Path temp) throws Exception {
        Path target = Files.writeString(temp.resolve("real.password"), "linked-secret\n");
        Path link = temp.resolve("link.password");
        Files.createSymbolicLink(link, target);

        IOException failure = assertThrows(IOException.class, () -> resolve(temp.resolve("home"), link));

        assertFalse(failure.getMessage().contains(temp.toString()), "the message names a path: " + failure.getMessage());
        assertFalse(failure.getMessage().contains("linked-secret"));
    }

    @Test
    void anAbsoluteSecretThatIsADirectoryIsRefusedWithoutNamingItsPath(@TempDir Path temp) {
        IOException failure = assertThrows(IOException.class, () -> resolve(temp.resolve("home"), temp));

        assertFalse(failure.getMessage().contains(temp.toString()), failure.getMessage());
    }
}
