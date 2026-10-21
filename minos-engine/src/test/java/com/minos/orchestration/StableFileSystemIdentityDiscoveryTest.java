package com.minos.orchestration;

import com.minos.orchestration.ExecutionPathIdentityProvider.IdentityPair;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AUD-ARC-04 : le fournisseur d'identité de chemin est découvert par un chargeur explicite, et plus d'un
 * fournisseur rend l'identité indisponible (donc le lancement refusé) au lieu de prendre le premier.
 */
class StableFileSystemIdentityDiscoveryTest {

    @TempDir
    Path temp;

    @Test
    void twoProvidersMakeTheIdentityUnavailable() throws IOException {
        try (URLClassLoader loader = loaderDeclaring(FixedIdentity.class, OtherFixedIdentity.class)) {
            assertTrue(StableFileSystemIdentity.providedIdentity(temp, temp, loader).isEmpty());
        }
    }

    @Test
    void oneProviderIsConsulted() throws IOException {
        try (URLClassLoader loader = loaderDeclaring(FixedIdentity.class)) {
            assertEquals(Optional.of(new IdentityPair("fixed:a", "fixed:b")),
                    StableFileSystemIdentity.providedIdentity(temp, temp, loader));
        }
    }

    @Test
    void noProviderGivesNoIdentity() throws IOException {
        try (URLClassLoader loader = new URLClassLoader(new URL[0], null)) {
            assertTrue(StableFileSystemIdentity.providedIdentity(temp, temp, loader).isEmpty());
        }
    }

    private URLClassLoader loaderDeclaring(Class<?>... providers) throws IOException {
        Path services = temp.resolve("services");
        Path declaration = services.resolve("META-INF/services/" + ExecutionPathIdentityProvider.class.getName());
        Files.createDirectories(declaration.getParent());
        StringBuilder lines = new StringBuilder();
        for (Class<?> provider : providers) {
            lines.append(provider.getName()).append('\n');
        }
        Files.writeString(declaration, lines, StandardCharsets.UTF_8);
        return new URLClassLoader(new URL[]{services.toUri().toURL()}, getClass().getClassLoader());
    }

    /** Double qui rend toujours une identité. */
    public static final class FixedIdentity implements ExecutionPathIdentityProvider {
        @Override
        public Optional<IdentityPair> capture(Path registeredProjectRoot, Path projectRoot) {
            return Optional.of(new IdentityPair("fixed:a", "fixed:b"));
        }
    }

    /** Second double qui rend toujours une identité. */
    public static final class OtherFixedIdentity implements ExecutionPathIdentityProvider {
        @Override
        public Optional<IdentityPair> capture(Path registeredProjectRoot, Path projectRoot) {
            return Optional.of(new IdentityPair("other:a", "other:b"));
        }
    }
}
