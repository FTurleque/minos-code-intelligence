package com.minos.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.AssertionFailedError;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * T1 (ADR 0068 section 4): an application composed with the local backend runs without PostgreSQL and loads no class of
 * the PostgreSQL backend nor of its driver. The composition runs in an isolated class loader that defines every MINOS
 * and PostgreSQL class itself and records them, so classes loaded by other tests of the same JVM do not count.
 */
class LocalCompositionLoadsNoPostgresqlClassTest {

    private static final List<String> POSTGRESQL_PREFIXES = List.of("org.postgresql.", "com.minos.storage.postgresql.");

    @Test
    void theLocalCompositionLoadsNoPostgresqlClass(@TempDir Path temp) throws Exception {
        RecordingClassLoader loader = new RecordingClassLoader(getClass().getClassLoader());

        assertEquals("local", openAndReadBackend(loader, temp.resolve("home")));

        assertNoPostgresqlClassLoaded(loader);
        assertTrue(loader.defined().contains("com.minos.application.MinosApplication"),
                "the composition must run inside the isolated loader");
    }

    @Test
    void theCheckNamesAPostgresqlClassLoadedByTheComposition(@TempDir Path temp) throws Exception {
        RecordingClassLoader loader = new RecordingClassLoader(getClass().getClassLoader());
        openAndReadBackend(loader, temp.resolve("home"));
        Class.forName("com.minos.storage.postgresql.PostgresStorageBackend", false, loader);

        AssertionFailedError failure = assertThrows(AssertionFailedError.class, () -> assertNoPostgresqlClassLoaded(loader));

        assertTrue(failure.getMessage().contains("com.minos.storage.postgresql.PostgresStorageBackend"), failure.getMessage());
    }

    private static String openAndReadBackend(ClassLoader loader, Path home) throws Exception {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            Class<?> application = Class.forName("com.minos.application.MinosApplication", true, loader);
            try (AutoCloseable opened = (AutoCloseable) application.getMethod("open", Path.class).invoke(null, home)) {
                return (String) application.getMethod("storageBackendId").invoke(opened);
            }
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    private static void assertNoPostgresqlClassLoaded(RecordingClassLoader loader) {
        Set<String> loaded = new TreeSet<>();
        for (String name : loader.defined()) {
            if (POSTGRESQL_PREFIXES.stream().anyMatch(name::startsWith)) {
                loaded.add(name);
            }
        }
        if (!loaded.isEmpty()) {
            fail("the local composition loaded PostgreSQL classes: " + loaded);
        }
    }

    /** Defines MINOS and PostgreSQL classes itself (child first) and records their names; delegates everything else. */
    private static final class RecordingClassLoader extends ClassLoader {
        private final Set<String> defined = java.util.concurrent.ConcurrentHashMap.newKeySet();

        RecordingClassLoader(ClassLoader parent) {
            super(parent);
        }

        Set<String> defined() {
            return Set.copyOf(defined);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith("com.minos.") && !name.startsWith("org.postgresql.")) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    loaded = defineFromParentBytes(name);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }

        private Class<?> defineFromParentBytes(String name) throws ClassNotFoundException {
            String resource = name.replace('.', '/') + ".class";
            try (InputStream input = getParent().getResourceAsStream(resource)) {
                if (input == null) {
                    throw new ClassNotFoundException(name);
                }
                byte[] bytes = input.readAllBytes();
                Class<?> definedClass = defineClass(name, bytes, 0, bytes.length);
                defined.add(name);
                return definedClass;
            } catch (IOException exception) {
                throw new ClassNotFoundException(name, exception);
            }
        }
    }
}
