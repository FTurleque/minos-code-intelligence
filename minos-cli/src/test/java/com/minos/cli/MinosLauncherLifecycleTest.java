package com.minos.cli;

import com.minos.application.MinosApplication;
import com.minos.storage.StorageBackend;
import com.minos.storage.local.LocalStorageBackend;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Q10 : l'application que le lanceur ouvre pour une commande lui appartient. Elle est fermée exactement une fois
 * sur tous les chemins de sortie (succès, code d'erreur d'usage, échec d'exécution, exception), et jamais
 * ouverte pour les commandes qui n'en ont pas besoin. La fermeture est observée sur le magasin de stockage,
 * que {@link MinosApplication#close()} ferme et dont un espion compte les appels.
 */
class MinosLauncherLifecycleTest {

    @Test
    void theApplicationIsClosedAfterASuccessfulCommand(@TempDir Path home) {
        Launch launch = launch(home, new String[]{"project", "list", "--format", "json"}, MinosLauncher::run, null);

        assertEquals(FindSymbolCommand.SUCCESS, launch.exit(), launch.error());
        assertEquals(List.of("open", "close"), launch.events(), "une ouverture, une fermeture");
        assertEquals("", launch.error());
    }

    @Test
    void theApplicationIsClosedAfterAnUsageError(@TempDir Path home) {
        Launch launch = launch(home, new String[]{"find-symbol", "--bogus"}, MinosLauncher::run, null);

        assertEquals(FindSymbolCommand.USAGE_ERROR, launch.exit(), launch.error());
        assertEquals(List.of("open", "close"), launch.events());
    }

    @Test
    void theApplicationIsClosedWhenTheCommandThrows(@TempDir Path home) {
        Launch launch = launch(home, new String[]{"project", "list"}, (application, arguments, output, error) -> {
            throw new IOException("boom");
        }, null);

        assertEquals(FindSymbolCommand.EXECUTION_ERROR, launch.exit());
        assertEquals("error: MINOS bootstrap failed: boom", launch.error().strip());
        assertEquals(List.of("open", "close"), launch.events());
    }

    @Test
    void theApplicationIsClosedWhenTheCommandFailsWithARuntimeException(@TempDir Path home) {
        Launch launch = launch(home, new String[]{"project", "list"}, (application, arguments, output, error) -> {
            throw new IllegalStateException("broken");
        }, null);

        assertEquals(FindSymbolCommand.EXECUTION_ERROR, launch.exit());
        assertEquals("error: MINOS bootstrap failed: broken", launch.error().strip());
        assertEquals(List.of("open", "close"), launch.events());
    }

    @Test
    void aFailureToReleaseTheApplicationIsReportedInsteadOfBeingLost(@TempDir Path home) {
        Launch launch = launch(home, new String[]{"project", "list", "--format", "json"}, MinosLauncher::run,
                new IOException("storage refused to close"));

        assertEquals(FindSymbolCommand.EXECUTION_ERROR, launch.exit());
        assertTrue(launch.error().contains("error: MINOS bootstrap failed: storage refused to close"), launch.error());
        assertEquals(List.of("open", "close"), launch.events());
    }

    @Test
    void commandsThatNeedNoApplicationNeverOpenOne(@TempDir Path home) {
        for (String[] arguments : new String[][]{
                {"--version"}, {"--help"}, {"doctor", "--help"}, {"tools", "install", "--help"},
                {"ide", "handshake", "--format", "json"}}) {
            Launch launch = launch(home, arguments, MinosLauncher::run, null);

            assertEquals(FindSymbolCommand.SUCCESS, launch.exit(), String.join(" ", arguments) + " " + launch.error());
            assertEquals(List.of(), launch.events(), String.join(" ", arguments));
        }
    }

    private static Launch launch(
            Path home, String[] arguments, MinosLauncher.CommandRunner runner, IOException closeFailure) {
        List<String> events = new ArrayList<>();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        int exit;
        try (PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
             PrintStream err = new PrintStream(error, true, StandardCharsets.UTF_8)) {
            exit = MinosLauncher.launch(arguments,
                    Map.of(MinosLauncher.HOME_ENVIRONMENT_VARIABLE, home.toString()), new Properties(), out, err,
                    resolved -> {
                        events.add("open");
                        return open(resolved, events, closeFailure);
                    },
                    runner);
        }
        return new Launch(exit, output.toString(StandardCharsets.UTF_8), error.toString(StandardCharsets.UTF_8), events);
    }

    private static MinosApplication open(Path home, List<String> events, IOException closeFailure) throws IOException {
        StorageBackend backend = closeSpy(new LocalStorageBackend(home), events, closeFailure);
        return MinosApplication.builder(home).storageBackend(backend).build();
    }

    /** Un magasin de stockage qui enregistre chaque fermeture, et délègue tout le reste au magasin réel. */
    private static StorageBackend closeSpy(StorageBackend delegate, List<String> events, IOException closeFailure) {
        InvocationHandler handler = (proxy, method, arguments) -> {
            if ("close".equals(method.getName())) {
                events.add("close");
                if (closeFailure != null) throw closeFailure;
            }
            try {
                return method.invoke(delegate, arguments);
            } catch (InvocationTargetException exception) {
                throw exception.getCause();
            }
        };
        return (StorageBackend) Proxy.newProxyInstance(
                StorageBackend.class.getClassLoader(), new Class<?>[]{StorageBackend.class}, handler);
    }

    private record Launch(int exit, String output, String error, List<String> events) { }
}
