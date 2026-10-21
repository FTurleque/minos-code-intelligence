package com.minos.bootstrap;

import com.minos.storage.StorageBackend;
import com.minos.storage.StorageBackendConfiguration;
import com.minos.storage.StorageBackendProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AUD-ARC-04 : la découverte du backend de stockage optionnel utilise le chargeur de MINOS, jamais le chargeur de
 * contexte du thread, et refuse deux fournisseurs de l'identifiant demandé au lieu de prendre le premier.
 */
class StorageBackendSelectionTest {

    private static final String REAL_PROVIDER = "com.minos.storage.postgresql.PostgresStorageBackendProvider";

    @TempDir
    Path temp;

    @Test
    void emptyContextClassLoaderStillFindsThePostgresqlProvider() throws IOException {
        StorageBackendConfiguration configuration = configuration("postgresql");
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(new URLClassLoader(new URL[0], null));
        try {
            StorageBackendProvider provider = StorageBackendSelection.provider(
                    configuration.backend(), StorageBackendSelection.minosClassLoader());
            assertEquals(REAL_PROVIDER, provider.getClass().getName());
            // Le chemin public aussi atteint le vrai fournisseur (qui échoue ensuite faute de configuration),
            // pas le refus « provider is not available ».
            Exception failure = assertThrows(Exception.class, () -> StorageBackendSelection.open(configuration));
            assertFalse(String.valueOf(failure.getMessage()).contains("provider is not available"),
                    "the context class loader must not drive discovery: " + failure.getMessage());
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    @Test
    void twoProvidersOfTheRequestedIdAreRefusedByName() throws IOException {
        try (URLClassLoader loader = loaderDeclaring(FirstDuplicate.class, SecondDuplicate.class)) {
            IOException failure = assertThrows(IOException.class,
                    () -> StorageBackendSelection.provider("POSTGRESQL", loader));
            String message = failure.getMessage();
            assertTrue(message.contains(FirstDuplicate.class.getName()), message);
            assertTrue(message.contains(SecondDuplicate.class.getName()), message);
            assertTrue(message.contains(REAL_PROVIDER), message);
            assertFalse(message.contains(temp.toString()), message);
            assertFalse(message.contains(temp.toAbsolutePath().toString()), message);
        }
    }

    @Test
    void duplicatesDoNotAffectTheLocalBackend() throws Exception {
        try (URLClassLoader loader = loaderDeclaring(FirstDuplicate.class, SecondDuplicate.class);
             StorageBackend backend = StorageBackendSelection.open(configuration("local"), loader)) {
            assertNotNull(backend);
        }
    }

    @Test
    void missingProviderKeepsTheExistingRefusal() {
        IOException failure = assertThrows(IOException.class,
                () -> StorageBackendSelection.open(configuration("postgresql"), new URLClassLoader(new URL[0], null)));
        assertEquals("MINOS storage backend provider is not available: postgresql", failure.getMessage());
    }

    private StorageBackendConfiguration configuration(String backend) {
        return new StorageBackendConfiguration(backend, temp.resolve("home"), null, null, null, null);
    }

    private URLClassLoader loaderDeclaring(Class<?>... providers) throws IOException {
        Path services = temp.resolve("services");
        Path declaration = services.resolve("META-INF/services/" + StorageBackendProvider.class.getName());
        Files.createDirectories(declaration.getParent());
        StringBuilder lines = new StringBuilder();
        for (Class<?> provider : providers) {
            lines.append(provider.getName()).append('\n');
        }
        Files.writeString(declaration, lines, StandardCharsets.UTF_8);
        return new URLClassLoader(new URL[]{services.toUri().toURL()}, getClass().getClassLoader());
    }

    /** Double d'identifiant {@code postgresql}. */
    public static final class FirstDuplicate implements StorageBackendProvider {
        @Override public String id() { return "postgresql"; }

        @Override public StorageBackend open(StorageBackendConfiguration configuration) {
            throw new AssertionError("a duplicate provider must never be opened");
        }
    }

    /** Second double d'identifiant {@code PostgreSQL}, comparé sans casse. */
    public static final class SecondDuplicate implements StorageBackendProvider {
        @Override public String id() { return "PostgreSQL"; }

        @Override public StorageBackend open(StorageBackendConfiguration configuration) {
            throw new AssertionError("a duplicate provider must never be opened");
        }
    }
}
