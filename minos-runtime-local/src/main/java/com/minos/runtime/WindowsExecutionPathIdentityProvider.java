package com.minos.runtime;

import com.minos.orchestration.ExecutionPathIdentityProvider;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Non-elevated Win32 implementation of the engine execution-path identity SPI. */
public final class WindowsExecutionPathIdentityProvider implements ExecutionPathIdentityProvider {

    private static final String RESOURCE = "/com/minos/runtime/windows-path-identity-v1.ps1";
    private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration OUTPUT_DRAIN_TIMEOUT = Duration.ofSeconds(2);
    private static final int OUTPUT_LIMIT = 8 * 1024;
    private static final Pattern REGISTERED =
            Pattern.compile("(?m)^registered=([0-9a-fA-F]{8}:[0-9a-fA-F]{16})\\R?$");
    private static final Pattern PROJECT =
            Pattern.compile("(?m)^project=([0-9a-fA-F]{8}:[0-9a-fA-F]{16})\\R?$");

    @Override
    public Optional<IdentityPair> capture(Path registeredProjectRoot, Path projectRoot) throws IOException {
        if (WorkerSandboxQualification.currentPlatform() != WorkerSandboxQualification.Platform.WINDOWS) {
            return Optional.empty();
        }
        Optional<Path> powershell = CommandLocator.windowsPowerShell();
        if (powershell.isEmpty()) {
            return Optional.empty();
        }

        ProcessBuilder builder = new ProcessBuilder(List.of(
                powershell.orElseThrow().toString(),
                "-NoLogo",
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy",
                "Bypass",
                "-EncodedCommand",
                encodedHelper()));
        builder.redirectErrorStream(true);
        builder.environment().put(
                "MINOS_REGISTERED_PATH_IDENTITY_TARGET", registeredProjectRoot.toString());
        builder.environment().put(
                "MINOS_PROJECT_PATH_IDENTITY_TARGET", projectRoot.toString());

        Process process = builder.start();
        try (ExecutorService outputExecutor = Executors.newSingleThreadExecutor(
                Thread.ofPlatform()
                        .name("minos-windows-path-identity-output")
                        .daemon(true)
                        .factory())) {
            Future<byte[]> output = outputExecutor.submit(() -> drainBounded(process.getInputStream()));
            boolean completed;
            try {
                completed = process.waitFor(QUERY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while capturing Windows filesystem identity", interrupted);
            }
            if (!completed) {
                process.destroyForcibly();
                waitForTermination(process);
                throw new IOException("Windows filesystem identity query timed out");
            }

            byte[] bytes = awaitOutput(output);
            if (bytes.length > OUTPUT_LIMIT) {
                throw new IOException("Windows filesystem identity output exceeded its bound");
            }
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                throw new IOException("Windows filesystem identity query failed: " + boundedDiagnostic(text));
            }
            Matcher registered = REGISTERED.matcher(text);
            Matcher project = PROJECT.matcher(text);
            if (!registered.find() || !project.find()) {
                throw new IOException("Windows filesystem identity helper returned an invalid response");
            }
            return Optional.of(new IdentityPair(
                    "windows:" + registered.group(1).toLowerCase(Locale.ROOT),
                    "windows:" + project.group(1).toLowerCase(Locale.ROOT)));
        }
    }

    /**
     * Drains the trusted helper's pipe to EOF while retaining at most limit + 1 bytes.
     *
     * <p>Draining concurrently prevents a full pipe from stalling the helper, while retaining one
     * byte beyond the limit lets the caller distinguish bounded output from overflow without ever
     * writing helper diagnostics into the publicly writable system temporary directory.</p>
     */
    private static byte[] drainBounded(InputStream input) throws IOException {
        try (InputStream stream = input;
             ByteArrayOutputStream captured = new ByteArrayOutputStream(OUTPUT_LIMIT + 1)) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                int remaining = OUTPUT_LIMIT + 1 - captured.size();
                if (remaining > 0) {
                    captured.write(buffer, 0, Math.min(read, remaining));
                }
            }
            return captured.toByteArray();
        }
    }

    private static byte[] awaitOutput(Future<byte[]> output) throws IOException {
        try {
            return output.get(OUTPUT_DRAIN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while draining Windows filesystem identity output", interrupted);
        } catch (TimeoutException timeout) {
            output.cancel(true);
            throw new IOException("Windows filesystem identity output did not drain after helper exit", timeout);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof IOException ioFailure) {
                throw ioFailure;
            }
            throw new IOException("failed to drain Windows filesystem identity output", cause);
        }
    }

    private static String encodedHelper() throws IOException {
        try (InputStream input = WindowsExecutionPathIdentityProvider.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IOException("packaged Windows path identity helper is missing");
            }
            String script = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            return Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        }
    }

    private static void waitForTermination(Process process) {
        try {
            process.waitFor(1, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static String boundedDiagnostic(String text) {
        String normalized = Optional.ofNullable(text).orElse("")
                .replace('\r', ' ')
                .replace('\n', ' ')
                .trim();
        if (normalized.length() <= 512) return normalized;
        return normalized.substring(0, 512) + "…";
    }
}
