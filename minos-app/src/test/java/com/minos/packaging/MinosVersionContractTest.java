package com.minos.packaging;

import com.minos.runtime.MinosVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * A3 / ADR 0044 — contrat de {@link MinosVersion}, indépendant du module qui porte la classe.
 *
 * <p>La version observable vient de l'attribut {@code Implementation-Version} du manifeste du JAR qui
 * contient la classe (le JAR de {@code minos-app} et le JAR ombré le posent) ; hors d'un tel JAR, la
 * version de développement sert de repli. Déplacer la classe d'un module à l'autre ne doit changer ni
 * l'une ni l'autre.</p>
 */
class MinosVersionContractTest {

    private static final String DEVELOPMENT_VERSION = "1.0.1-SNAPSHOT";

    @Test
    void outsideAVersionedJarTheDevelopmentVersionIsTheFallback() {
        assertEquals(DEVELOPMENT_VERSION, MinosVersion.current());
    }

    @Test
    void insideAJarTheManifestImplementationVersionWins(@TempDir Path temporary) throws Exception {
        assertEquals("9.8.7-contract", versionFromJar(temporary, "9.8.7-contract"));
    }

    @Test
    void aBlankManifestVersionFallsBackToTheDevelopmentVersion(@TempDir Path temporary) throws Exception {
        assertEquals(DEVELOPMENT_VERSION, versionFromJar(temporary, " "));
    }

    /** Copie la classe dans un JAR isolé dont le manifeste porte la version donnée, puis l'y exécute. */
    private static String versionFromJar(Path temporary, String implementationVersion) throws Exception {
        String entry = MinosVersion.class.getName().replace('.', '/') + ".class";
        Path jar = temporary.resolve("versioned.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_VERSION, implementationVersion);
        try (InputStream classBytes = MinosVersion.class.getClassLoader().getResourceAsStream(entry);
             OutputStream file = Files.newOutputStream(jar);
             JarOutputStream output = new JarOutputStream(file, manifest)) {
            assertNotNull(classBytes, entry);
            output.putNextEntry(new JarEntry(entry));
            classBytes.transferTo(output);
            output.closeEntry();
        }
        try (URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()},
                ClassLoader.getPlatformClassLoader())) {
            Class<?> isolated = Class.forName(MinosVersion.class.getName(), true, loader);
            assertEquals(loader, isolated.getClassLoader());
            Method current = isolated.getMethod("current");
            return (String) current.invoke(null);
        }
    }
}
