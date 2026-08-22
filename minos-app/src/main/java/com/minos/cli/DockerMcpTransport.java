package com.minos.cli;

import com.minos.runtime.CommandLocator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Docker STDIO transport used only after the Docker backend has been selected explicitly. */
final class DockerMcpTransport {

    private static final int MAX_PROBE_OUTPUT_BYTES = 64 * 1024;
    private static final int PROBE_READ_BUFFER_BYTES = 4096;

    interface ProcessExecutor {
        ProcessResult probe(List<String> command, Duration timeout) throws IOException, InterruptedException;
        int attach(List<String> command) throws IOException, InterruptedException;
    }

    @FunctionalInterface
    interface DockerExecutableResolver {
        Path resolve() throws IOException;
    }

    record ProcessResult(int exitCode, String output) { }

    private final ProcessExecutor processes;
    private final DockerExecutableResolver dockerExecutableResolver;

    DockerMcpTransport() {
        this(new SystemProcessExecutor(), DockerMcpTransport::resolveDockerExecutable);
    }

    /** Test seam: fake executors receive the historical logical command name without touching PATH. */
    DockerMcpTransport(ProcessExecutor processes) {
        this(processes, () -> Path.of("docker"));
    }

    DockerMcpTransport(ProcessExecutor processes, DockerExecutableResolver dockerExecutableResolver) {
        this.processes = Objects.requireNonNull(processes, "processes");
        this.dockerExecutableResolver = Objects.requireNonNull(dockerExecutableResolver, "dockerExecutableResolver");
    }

    int run(McpBackendConfiguration configuration) throws IOException, InterruptedException {
        Objects.requireNonNull(configuration, "configuration");
        if (configuration.backend() != McpBackend.DOCKER) {
            throw new IllegalArgumentException("Docker MCP transport requires backend=docker");
        }

        Path docker = Objects.requireNonNull(dockerExecutableResolver.resolve(), "docker executable");
        ProcessResult daemon = processes.probe(
                dockerCommand(docker, "version", "--format", "{{.Server.Version}}"),
                configuration.dockerProbeTimeout());
        if (daemon.exitCode() != 0 || daemon.output().isBlank()) {
            throw new IOException("Docker backend selected but Docker daemon is unavailable"
                    + diagnosticSuffix(daemon.output()));
        }

        String containerName = validatedContainerName(configuration.dockerContainerName());
        ProcessResult container = processes.probe(
                dockerCommand(docker, "inspect", "--format", "{{.State.Running}}", containerName),
                configuration.dockerProbeTimeout());
        if (container.exitCode() != 0 || !"true".equalsIgnoreCase(container.output().trim())) {
            throw new IOException("Docker backend selected but MINOS container is not running: "
                    + containerName + diagnosticSuffix(container.output()));
        }

        int exitCode = processes.attach(dockerCommand(
                docker, "exec", "-i", containerName,
                "java", "-cp", "/opt/minos/minos.jar", "com.minos.mcp.MinosMcpServer"));
        if (exitCode != 0 && exitCode != 130) {
            throw new IOException("Docker MCP STDIO session failed with exit code " + exitCode);
        }
        return exitCode == 130 ? FindSymbolCommand.SUCCESS : exitCode;
    }

    private static List<String> dockerCommand(Path executable, String... arguments) {
        return CommandLocator.invocation(executable, arguments);
    }

    private static Path resolveDockerExecutable() throws IOException {
        return CommandLocator.find("docker")
                .orElseThrow(() -> new IOException("Docker backend selected but Docker executable is unavailable"));
    }

    private static String diagnosticSuffix(String output) {
        if (output == null || output.isBlank()) return "";
        String compact = output.replace('\r', ' ').replace('\n', ' ').trim();
        return compact.isEmpty() ? "" : ": " + compact;
    }

    /** Defence-in-depth validation matching the configuration contract. */
    private static String validatedContainerName(String name) {
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("invalid Docker container name: " + name);
        }
        return name;
    }

    private static final class SystemProcessExecutor implements ProcessExecutor {
        @Override
        public ProcessResult probe(List<String> command, Duration timeout) throws IOException, InterruptedException {
            requireAbsoluteExecutable(command);
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            Process process;
            try {
                process = builder.start();
            } catch (IOException exception) {
                throw new IOException("Docker backend selected but Docker executable cannot be started", exception);
            }
            try (ByteArrayOutputStream output = new ByteArrayOutputStream(MAX_PROBE_OUTPUT_BYTES)) {
                AtomicReference<IOException> readerFailure = new AtomicReference<>();
                Thread reader = Thread.ofPlatform()
                        .daemon(true)
                        .name("minos-docker-probe-output")
                        .start(() -> {
                            try {
                                drainBounded(process.getInputStream(), output);
                            } catch (IOException exception) {
                                readerFailure.set(exception);
                            }
                        });
                try {
                    boolean completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
                    if (!completed) {
                        process.destroyForcibly();
                        process.waitFor();
                        throw new IOException("Docker backend probe timed out after " + timeout.toMillis() + " ms");
                    }
                    joinReaderBounded(process, reader);
                    IOException readFailure = readerFailure.get();
                    if (readFailure != null) {
                        throw new IOException("Docker backend probe output could not be read", readFailure);
                    }
                    return new ProcessResult(process.exitValue(), output.toString(StandardCharsets.UTF_8).trim());
                } catch (InterruptedException exception) {
                    process.destroyForcibly();
                    Thread.currentThread().interrupt();
                    throw exception;
                }
            }
        }

        private static void joinReaderBounded(Process process, Thread reader)
                throws IOException, InterruptedException {
            reader.join(5_000L);
            if (!reader.isAlive()) return;
            try {
                process.getInputStream().close();
            } catch (IOException ignored) {
                // The process may already have closed its direct pipe.
            }
            reader.join(5_000L);
            if (reader.isAlive()) throw new IOException("Docker backend probe output drain did not terminate");
        }

        private static void drainBounded(InputStream source, ByteArrayOutputStream captured) throws IOException {
            try (InputStream input = source) {
                byte[] buffer = new byte[PROBE_READ_BUFFER_BYTES];
                int retained = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (retained < MAX_PROBE_OUTPUT_BYTES) {
                        int keep = Math.min(read, MAX_PROBE_OUTPUT_BYTES - retained);
                        captured.write(buffer, 0, keep);
                        retained += keep;
                    }
                    // Bytes beyond the capture limit are deliberately drained and discarded so the
                    // child cannot block on a full stdout pipe or grow MINOS memory without bound.
                }
            }
        }

        @Override
        public int attach(List<String> command) throws IOException, InterruptedException {
            requireAbsoluteExecutable(command);
            Process process;
            try {
                process = new ProcessBuilder(command).inheritIO().start();
            } catch (IOException exception) {
                throw new IOException("Docker backend selected but Docker MCP session cannot be started", exception);
            }
            try {
                return process.waitFor();
            } catch (InterruptedException exception) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw exception;
            }
        }

        private static void requireAbsoluteExecutable(List<String> command) throws IOException {
            if (command == null || command.isEmpty() || command.getFirst() == null) {
                throw new IOException("Docker process command is empty");
            }
            Path configured = Path.of(command.getFirst());
            Path executable = configured.toAbsolutePath().normalize();
            if (!configured.isAbsolute() || !Files.isRegularFile(executable)) {
                throw new IOException("Docker process executable must be an existing absolute file");
            }
        }
    }
}
