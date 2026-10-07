package com.minos.app;

import com.minos.application.MinosApplication;
import com.minos.storage.StorageBackend;
import com.minos.storage.local.LocalStorageBackend;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Q10 : le routeur du serveur MCP possède l'application qu'il ouvre. Elle reste ouverte tant que la session MCP
 * est servie, et elle est fermée exactement une fois à la sortie, que la session se termine normalement ou par
 * une exception. La fermeture est observée sur le magasin de stockage, que {@link MinosApplication#close()}
 * ferme et dont un espion compte les appels.
 */
class McpBackendRouterLifecycleTest {

    @Test
    void theApplicationStaysOpenWhileTheSessionIsServedAndIsClosedOnceAfterwards(@TempDir Path home) throws Exception {
        List<String> events = new ArrayList<>();
        McpBackendRouter.NativeMcpRunner runner = McpBackendRouter.serving(
                opened -> openWithSpy(opened, events),
                application -> events.add("served"));

        runner.run(home.resolve("minos-home"));

        assertEquals(List.of("served", "close"), events,
                "l'application n'est fermée qu'une fois la session terminée, et une seule fois");
    }

    @Test
    void theApplicationIsClosedWhenTheSessionFails(@TempDir Path home) {
        List<String> events = new ArrayList<>();
        IllegalStateException failure = new IllegalStateException("session failed");
        McpBackendRouter.NativeMcpRunner runner = McpBackendRouter.serving(
                opened -> openWithSpy(opened, events),
                application -> {
                    events.add("served");
                    throw failure;
                });

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> runner.run(home.resolve("minos-home")));

        assertSame(failure, thrown, "l'échec de la session est propagé tel quel");
        assertEquals(List.of("served", "close"), events);
    }

    @Test
    void nothingIsServedNorClosedWhenTheApplicationCannotBeOpened(@TempDir Path home) {
        List<String> events = new ArrayList<>();
        IOException failure = new IOException("cannot open");
        McpBackendRouter.NativeMcpRunner runner = McpBackendRouter.serving(
                opened -> {
                    throw failure;
                },
                application -> events.add("served"));

        IOException thrown = assertThrows(IOException.class, () -> runner.run(home.resolve("minos-home")));

        assertSame(failure, thrown);
        assertEquals(List.of(), events);
    }

    @Test
    void theNativeBackendOfTheRouterClosesItsApplicationOnceTheSessionEnds(@TempDir Path home) throws Exception {
        List<String> events = new ArrayList<>();
        McpBackendRouter router = new McpBackendRouter(
                McpBackendRouter.serving(opened -> openWithSpy(opened, events), application -> events.add("served")),
                new DockerMcpTransport(),
                path -> path);

        int exit = router.run(home);

        assertEquals(0, exit);
        assertEquals(List.of("served", "close"), events);
    }

    private static MinosApplication openWithSpy(Path home, List<String> events) throws IOException {
        StorageBackend backend = closeSpy(new LocalStorageBackend(home), events);
        return MinosApplication.builder(home).storageBackend(backend).build();
    }

    /** Un magasin de stockage qui enregistre chaque fermeture, et délègue tout le reste au magasin réel. */
    private static StorageBackend closeSpy(StorageBackend delegate, List<String> events) {
        InvocationHandler handler = (proxy, method, arguments) -> {
            if ("close".equals(method.getName())) events.add("close");
            try {
                return method.invoke(delegate, arguments);
            } catch (InvocationTargetException exception) {
                throw exception.getCause();
            }
        };
        return (StorageBackend) Proxy.newProxyInstance(
                StorageBackend.class.getClassLoader(), new Class<?>[]{StorageBackend.class}, handler);
    }
}
