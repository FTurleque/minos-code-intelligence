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

    /** MINOS-AUD-B08 : un BOM en tete (Windows PowerShell 5.1) s'ajoutait au mot de passe, que PostgreSQL refusait. */
    @Test
    void anAbsoluteSecretWithALeadingByteOrderMarkIsReadWithoutIt(@TempDir Path temp) throws Exception {
        byte[] bytes = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 's', '3', 'c', 'r', 'e', 't', '\r', '\n'};
        Path secret = temp.resolve("bom.password");
        Files.write(secret, bytes);

        assertEquals("s3cret", resolve(temp.resolve("home"), secret).postgresPassword());
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

    /** A Kubernetes secret volume: key -> ..data/key, ..data -> a timestamped directory. */
    @Test
    void aLeafLinkToARegularSecretFileIsFollowedForTheOperatorDesignatedPath(@TempDir Path temp) throws Exception {
        Path generation = Files.createDirectories(temp.resolve("..2026_10_01"));
        Files.writeString(generation.resolve("password"), "mounted-secret\n");
        Path data = Files.createSymbolicLink(temp.resolve("..data"), generation.getFileName());
        Path key = Files.createSymbolicLink(temp.resolve("password"), Path.of("..data", "password"));

        assertEquals("mounted-secret", resolve(temp.resolve("home"), key).postgresPassword());
        assertEquals(data.getFileName().toString(), "..data");
    }

    @Test
    void aLeafLinkToADirectoryOrToNothingIsRefusedWithoutNamingAnyPath(@TempDir Path temp) throws Exception {
        Path directory = Files.createDirectories(temp.resolve("a-directory"));
        Path toDirectory = Files.createSymbolicLink(temp.resolve("to-directory"), directory);
        Path dangling = Files.createSymbolicLink(temp.resolve("dangling"), temp.resolve("nothing"));

        for (Path link : new Path[]{toDirectory, dangling}) {
            IOException failure = assertThrows(IOException.class, () -> resolve(temp.resolve("home"), link));
            assertFalse(failure.getMessage().contains(temp.toString()), "the message names a path: " + failure.getMessage());
        }
    }

    @Test
    void anAbsoluteSecretThatIsADirectoryIsRefusedWithoutNamingItsPath(@TempDir Path temp) {
        IOException failure = assertThrows(IOException.class, () -> resolve(temp.resolve("home"), temp));

        assertFalse(failure.getMessage().contains(temp.toString()), failure.getMessage());
    }
}
