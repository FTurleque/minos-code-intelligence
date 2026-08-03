package com.minos.cli;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Docker STDIO transport used only after the Docker backend has been selected explicitly. */
final class DockerMcpTransport {

    interface ProcessExecutor {
        ProcessResult probe(List<String> command, Duration timeout) throws IOException, InterruptedException;
        int attach(List<String> command) throws IOException, InterruptedException;
    }

    record ProcessResult(int exitCode, String output) { }

    private final ProcessExecutor processes;

    DockerMcpTransport() {
        this(new SystemProcessExecutor());
    }

    DockerMcpTransport(ProcessExecutor processes) {
        this.processes = Objects.requireNonNull(processes, "processes");
    }

    int run(McpBackendConfiguration configuration) throws IOException, InterruptedException {
        Objects.requireNonNull(configuration, "configuration");
        if (configuration.backend() != McpBackend.DOCKER) {
            throw new IllegalArgumentException("Docker MCP transport requires backend=docker");
        }

        ProcessResult daemon = processes.probe(
                List.of("docker", "version", "--format", "{{.Server.Version}}"),
                configuration.dockerProbeTimeout());
        if (daemon.exitCode() != 0 || daemon.output().isBlank()) {
            throw new IOException("Docker backend selected but Docker daemon is unavailable"
                    + diagnosticSuffix(daemon.output()));
        }

        ProcessResult container = processes.probe(
                List.of("docker", "inspect", "--format", "{{.State.Running}}", configuration.dockerContainerName()),
                configuration.dockerProbeTimeout());
        if (container.exitCode() != 0 || !"true".equalsIgnoreCase(container.output().trim())) {
            throw new IOException("Docker backend selected but MINOS container is not running: "
                    + configuration.dockerContainerName() + diagnosticSuffix(container.output()));
        }

        int exitCode = processes.attach(List.of(
                "docker", "exec", "-i", configuration.dockerContainerName(),
                "java", "-cp", "/opt/minos/minos.jar", "com.minos.mcp.MinosMcpServer"));
        if (exitCode != 0 && exitCode != 130) {
            throw new IOException("Docker MCP STDIO session failed with exit code " + exitCode);
        }
        return exitCode == 130 ? FindSymbolCommand.SUCCESS : exitCode;
    }

    private static String diagnosticSuffix(String output) {
        if (output == null || output.isBlank()) return "";
        String compact = output.replace('\r', ' ').replace('\n', ' ').trim();
        return compact.isEmpty() ? "" : ": " + compact;
    }

    private static final class SystemProcessExecutor implements ProcessExecutor {
        @Override
        public ProcessResult probe(List<String> command, Duration timeout) throws IOException, InterruptedException {
            // ProcessBuilder(List) passes each element as a separate OS argument — no shell expansion.
            // dockerContainerName is validated to [A-Za-z0-9][A-Za-z0-9_.-]+ by McpBackendConfiguration.
            ProcessBuilder builder = new ProcessBuilder(command); // NOSONAR
            builder.redirectErrorStream(true);
            Process process;
            try {
                process = builder.start(); // NOSONAR
            } catch (IOException exception) {
                throw new IOException("Docker backend selected but Docker executable cannot be started", exception);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Thread reader = Thread.ofVirtual().start(() -> {
                try {
                    process.getInputStream().transferTo(output);
                } catch (IOException ignored) {
                    // The process result below remains authoritative.
                }
            });
            try {
                boolean completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
                if (!completed) {
                    process.destroyForcibly();
                    process.waitFor();
                    throw new IOException("Docker backend probe timed out after " + timeout.toMillis() + " ms");
                }
                reader.join();
                return new ProcessResult(process.exitValue(), output.toString(StandardCharsets.UTF_8).trim());
            } catch (InterruptedException exception) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw exception;
            }
        }

        @Override
        public int attach(List<String> command) throws IOException, InterruptedException {
            Process process;
            try {
                process = new ProcessBuilder(command).inheritIO().start(); // NOSONAR
            } catch (IOException exception) {
                throw new IOException("Docker backend selected but Docker MCP session cannot be started", exception);
            }
            try {
                return process.waitFor();
            } catch (InterruptedException exception) {
                process.destroy();
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw exception;
            }
        }
    }
}
