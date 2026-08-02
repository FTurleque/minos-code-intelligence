package com.minos.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpBackendRouterTest {

    @Test
    void preM29HomeMigratesOnceToExplicitNativeConfiguration(@TempDir Path home) throws Exception {
        McpBackendConfigurationStore store = new McpBackendConfigurationStore(home);

        McpBackendConfiguration first = store.loadOrMigrate();
        McpBackendConfiguration second = store.loadOrMigrate();

        assertEquals(McpBackend.NATIVE, first.backend());
        assertEquals(first, second);
        assertTrue(Files.isRegularFile(store.file()));
        String persisted = Files.readString(store.file());
        assertTrue(persisted.contains("backend=native"));
        assertTrue(persisted.contains("formatVersion=1"));
    }

    @Test
    void invalidOrUnknownConfigurationFailsClosed(@TempDir Path home) throws Exception {
        McpBackendConfigurationStore store = new McpBackendConfigurationStore(home);
        Files.createDirectories(store.file().getParent());
        Files.writeString(store.file(), String.join("\n",
                "formatVersion=1",
                "backend=cloud",
                "docker.containerName=minos-mcp-prod",
                "docker.probeTimeoutMillis=10000"));

        IOException failure = assertThrows(IOException.class, store::loadOrMigrate);
        assertTrue(failure.getMessage().contains("unsupported MCP backend"));

        Files.writeString(store.file(), String.join("\n",
                "formatVersion=1",
                "backend=native",
                "docker.containerName=minos-mcp-prod",
                "docker.probeTimeoutMillis=10000",
                "unexpected=true"));
        IOException unknownKey = assertThrows(IOException.class, store::loadOrMigrate);
        assertTrue(unknownKey.getMessage().contains("unknown MCP backend configuration property"));
    }

    @Test
    void dockerUnavailableNeverFallsBackToNative(@TempDir Path home) throws Exception {
        new McpBackendConfigurationStore(home).save(dockerConfiguration());
        AtomicBoolean nativeCalled = new AtomicBoolean(false);
        FakeProcessExecutor processes = new FakeProcessExecutor();
        processes.probes.add(new DockerMcpTransport.ProcessResult(1, "daemon unavailable"));
        McpBackendRouter router = new McpBackendRouter(
                ignored -> nativeCalled.set(true),
                new DockerMcpTransport(processes));

        IOException failure = assertThrows(IOException.class, () -> router.run(home));

        assertTrue(failure.getMessage().contains("Docker daemon is unavailable"));
        assertFalse(nativeCalled.get(), "Docker failure must never execute native MCP");
        assertEquals(0, processes.attachCommands.size());
    }

    @Test
    void dockerBackendProbesDaemonAndContainerThenAttachesStdio(@TempDir Path home) throws Exception {
        new McpBackendConfigurationStore(home).save(dockerConfiguration());
        FakeProcessExecutor processes = new FakeProcessExecutor();
        processes.probes.add(new DockerMcpTransport.ProcessResult(0, "28.0.1"));
        processes.probes.add(new DockerMcpTransport.ProcessResult(0, "true"));
        processes.attachExitCode = 0;
        McpBackendRouter router = new McpBackendRouter(
                ignored -> { throw new AssertionError("native must not run"); },
                new DockerMcpTransport(processes));

        assertEquals(0, router.run(home));
        assertEquals(List.of(
                "docker", "version", "--format", "{{.Server.Version}}"), processes.probeCommands.get(0));
        assertEquals(List.of(
                "docker", "inspect", "--format", "{{.State.Running}}", "minos-mcp-prod"),
                processes.probeCommands.get(1));
        assertEquals(List.of(
                "docker", "exec", "-i", "minos-mcp-prod",
                "java", "-cp", "/opt/minos/minos.jar", "com.minos.mcp.MinosMcpServer"),
                processes.attachCommands.get(0));
    }

    @Test
    void nativeBackendRunsOnlyNativeMcp(@TempDir Path home) throws Exception {
        new McpBackendConfigurationStore(home).save(McpBackendConfiguration.nativeDefault());
        AtomicBoolean nativeCalled = new AtomicBoolean(false);
        FakeProcessExecutor processes = new FakeProcessExecutor();
        McpBackendRouter router = new McpBackendRouter(
                ignored -> nativeCalled.set(true),
                new DockerMcpTransport(processes));

        assertEquals(0, router.run(home));
        assertTrue(nativeCalled.get());
        assertTrue(processes.probeCommands.isEmpty());
        assertTrue(processes.attachCommands.isEmpty());
    }

    private static McpBackendConfiguration dockerConfiguration() {
        return new McpBackendConfiguration(
                McpBackendConfiguration.CURRENT_FORMAT_VERSION,
                McpBackend.DOCKER,
                "minos-mcp-prod",
                Duration.ofSeconds(10));
    }

    private static final class FakeProcessExecutor implements DockerMcpTransport.ProcessExecutor {
        private final Deque<DockerMcpTransport.ProcessResult> probes = new ArrayDeque<>();
        private final List<List<String>> probeCommands = new ArrayList<>();
        private final List<List<String>> attachCommands = new ArrayList<>();
        private int attachExitCode;

        @Override
        public DockerMcpTransport.ProcessResult probe(List<String> command, Duration timeout) {
            probeCommands.add(List.copyOf(command));
            if (probes.isEmpty()) throw new AssertionError("unexpected Docker probe: " + command);
            return probes.removeFirst();
        }

        @Override
        public int attach(List<String> command) {
            attachCommands.add(List.copyOf(command));
            return attachExitCode;
        }
    }
}
