package com.minos.app;

import com.minos.application.MinosApplication;
import com.minos.cli.ExplorerLaunchRoute;
import com.minos.cli.command.FindSymbolCommand;
import com.minos.storage.MinosRuntimeSettings;
import com.minos.storage.StorageBackend;
import com.minos.storage.local.LocalStorageBackend;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code minos explorer serve} route of the assembly: it is the one registered entry point, it refuses a
 * home on the Docker backend or in team mode before opening anything, and it owns the application it opens.
 */
class ExplorerLaunchRouteProviderTest {

    @Test
    void theAssemblyRegistersExactlyOneExplorerEntryPoint() {
        List<String> registered = ServiceLoader.load(ExplorerLaunchRoute.class, ExplorerLaunchRoute.class.getClassLoader())
                .stream().map(provider -> provider.type().getName()).toList();

        assertEquals(List.of(ExplorerLaunchRouteProvider.class.getName()), registered);
    }

    @Test
    void aNativeHomeIsOpenedForAsLongAsTheSurfaceServesAndClosedAfterwards(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        List<String> events = new ArrayList<>();
        ExplorerLaunchRouteProvider route = new ExplorerLaunchRouteProvider(
                validated -> {
                    events.add("opened");
                    return MinosApplication.builder(validated)
                            .storageBackend(closeSpy(new LocalStorageBackend(validated), events)).build();
                },
                (application, port, error) -> {
                    events.add("served on " + port);
                    return 0;
                });
        StringBuilder error = new StringBuilder();

        int exit = route.run(home, 8765, error);

        assertEquals(0, exit);
        assertEquals(List.of("opened", "served on 8765", "close"), events,
                "the application is closed once, after the surface has stopped");
        assertEquals("", error.toString());
    }

    @Test
    void theApplicationIsClosedWhenTheSurfaceFails(@TempDir Path temp) {
        List<String> events = new ArrayList<>();
        IllegalStateException failure = new IllegalStateException("surface failed");
        ExplorerLaunchRouteProvider route = new ExplorerLaunchRouteProvider(
                validated -> MinosApplication.builder(validated)
                        .storageBackend(closeSpy(new LocalStorageBackend(validated), events)).build(),
                (application, port, error) -> {
                    throw failure;
                });

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> route.run(temp.resolve("home"), 0, new StringBuilder()));

        assertTrue(thrown == failure);
        assertEquals(List.of("close"), events);
    }

    /** A storage backend that records each close and delegates everything else to the real one. */
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
                StorageBackend.class.getClassLoader(), new Class<?>[] {StorageBackend.class}, handler);
    }

    @Test
    void aHomeInTeamModeIsRefusedBeforeAnythingIsOpened(@TempDir Path temp) throws Exception {
        for (String value : List.of("enabled", "ENABLED", "anything-else")) {
            Path home = Files.createDirectories(temp.resolve("home-" + value));
            Path configuration = Files.createDirectories(home.resolve(MinosRuntimeSettings.CONFIG_DIRECTORY))
                    .resolve(MinosRuntimeSettings.CONFIG_FILE);
            Files.writeString(configuration, MinosApplication.HOSTED_MODE_PROPERTY + "=" + value + "\n",
                    StandardCharsets.UTF_8);
            StringBuilder error = new StringBuilder();

            int exit = refusing().run(home, 0, error);

            assertEquals(FindSymbolCommand.USAGE_ERROR, exit, value);
            assertTrue(error.toString().startsWith("error: minos explorer serve is not available when the team mode"),
                    error.toString());
            assertTrue(error.toString().contains("MINOS_HOSTED_MODE"), error.toString());
            assertFalse(error.toString().contains(temp.toString()), "absolute path in the refusal");
        }
    }

    @Test
    void aHomeWhoseTeamModeIsDisabledIsServed(@TempDir Path temp) throws Exception {
        Path home = Files.createDirectories(temp.resolve("home"));
        Path configuration = Files.createDirectories(home.resolve(MinosRuntimeSettings.CONFIG_DIRECTORY))
                .resolve(MinosRuntimeSettings.CONFIG_FILE);
        Files.writeString(configuration, MinosApplication.HOSTED_MODE_PROPERTY + "=disabled\n", StandardCharsets.UTF_8);
        List<Integer> served = new ArrayList<>();

        int exit = new ExplorerLaunchRouteProvider(MinosApplication::open, (application, port, error) -> {
            served.add(port);
            return 0;
        }).run(home, 0, new StringBuilder());

        assertEquals(0, exit);
        assertEquals(List.of(0), served);
    }

    @Test
    void aHomeOnTheDockerBackendIsNotOpenedOnTheHost(@TempDir Path temp) throws Exception {
        Path home = Files.createDirectories(temp.resolve("home"));
        McpBackendConfiguration docker = new McpBackendConfiguration(McpBackendConfiguration.CURRENT_FORMAT_VERSION,
                McpBackend.DOCKER, McpBackendConfiguration.DEFAULT_DOCKER_CONTAINER,
                McpBackendConfiguration.DEFAULT_DOCKER_PROBE_TIMEOUT);
        new McpBackendConfigurationStore(home).save(docker);
        StringBuilder error = new StringBuilder();

        int exit = refusing().run(home, 0, error);

        assertEquals(FindSymbolCommand.USAGE_ERROR, exit);
        assertTrue(error.toString().contains("Docker backend"), error.toString());
        assertTrue(error.toString().contains("profile explorer"), error.toString());
        assertFalse(error.toString().contains(temp.toString()), "absolute path in the refusal");
    }

    /** A route that fails the test if it ever opens an application or starts a surface. */
    private static ExplorerLaunchRouteProvider refusing() {
        return new ExplorerLaunchRouteProvider(
                home -> {
                    throw new AssertionError("an application was opened on a refused home");
                },
                (application, port, error) -> {
                    throw new AssertionError("a surface was started on a refused home");
                });
    }
}
