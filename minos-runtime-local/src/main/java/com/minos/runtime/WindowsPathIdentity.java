package com.minos.runtime;

import com.minos.io.PrivateLocalStorage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strong Windows directory identity backed by GetFileInformationByHandle.
 *
 * <p>The Java Windows filesystem provider can expose a null {@code BasicFileAttributes.fileKey()}.
 * Rather than weakening the anti-TOCTOU invariant, the local runtime asks Win32 for the volume
 * serial number and file index of each already-canonicalized directory. The pair is a physical
 * filesystem-object identity and does not require an elevated process.</p>
 */
final class WindowsPathIdentity {

    private static final String HELPER_NAME = "windows-path-identity-v1.ps1";
    private static final String RESOURCE = "/com/minos/runtime/" + HELPER_NAME;
    private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(15);
    private static final int OUTPUT_LIMIT = 8 * 1024;
    private static final Pattern REGISTERED = Pattern.compile("(?m)^registered=([0-9a-fA-F]{8}:[0-9a-fA-F]{16})\\R?$");
    private static final Pattern PROJECT = Pattern.compile("(?m)^project=([0-9a-fA-F]{8}:[0-9a-fA-F]{16})\\R?$");

    private final Path registeredProjectRoot;
    private final Path projectRoot;
    private final String registeredIdentity;
    private final String projectIdentity;
    private final Path powershell;
    private final Path helper;
    private final Path outputDirectory;

    private WindowsPathIdentity(
            Path registeredProjectRoot,
            Path projectRoot,
            String registeredIdentity,
            String projectIdentity,
            Path powershell,
            Path helper,
            Path outputDirectory
    ) {
        this.registeredProjectRoot = registeredProjectRoot;
        this.projectRoot = projectRoot;
        this.registeredIdentity = registeredIdentity;
        this.projectIdentity = projectIdentity;
        this.powershell = powershell;
        this.helper = helper;
        this.outputDirectory = outputDirectory;
    }

    static WindowsPathIdentity capture(Path minosHome, Path registeredProjectRoot, Path projectRoot) throws IOException {
        if (WorkerSandboxQualification.currentPlatform() != WorkerSandboxQualification.Platform.WINDOWS) {
            throw new IOException("Windows path identity requested on a non-Windows platform");
        }
        Path realRegistered = realPath(registeredProjectRoot, "registered project root");
        Path realProject = realPath(projectRoot, "project root");
        requireContained(realRegistered, realProject);

        Path home = Objects.requireNonNull(minosHome, "minosHome").toAbsolutePath().normalize();
        Path sandboxDirectory = PrivateLocalStorage.ensurePrivateDirectory(home.resolve("sandbox"));
        Path helper = installHelper(sandboxDirectory);
        Path powershell = CommandLocator.windowsPowerShell()
                .orElseThrow(() -> new IOException("PowerShell is required for strong Windows path identity"));
        IdentityPair captured = query(powershell, helper, sandboxDirectory, realRegistered, realProject);
        return new WindowsPathIdentity(
                realRegistered,
                realProject,
                captured.registered(),
                captured.project(),
                powershell,
                helper,
                sandboxDirectory);
    }

    void verifyCurrent(Path currentRegisteredProjectRoot, Path currentProjectRoot) throws IOException {
        Path realRegistered = realPath(currentRegisteredProjectRoot, "current registered project root");
        Path realProject = realPath(currentProjectRoot, "current project root");
        requireContained(realRegistered, realProject);
        IdentityPair current = query(powershell, helper, outputDirectory, realRegistered, realProject);
        if (!registeredProjectRoot.equals(realRegistered)
                || !projectRoot.equals(realProject)
                || !registeredIdentity.equals(current.registered())
                || !projectIdentity.equals(current.project())) {
            throw new IllegalStateException(
                    "provider execution path identity changed after canonical authorization");
        }
    }

    private static IdentityPair query(
            Path powershell,
            Path helper,
            Path outputDirectory,
            Path registeredProjectRoot,
            Path projectRoot
    ) throws IOException {
        Path output = PrivateLocalStorage.createPrivateTempFile(
                outputDirectory, ".windows-path-identity-", ".log");
        try {
            ProcessBuilder builder = new ProcessBuilder(List.of(
                    powershell.toString(),
                    "-NoLogo",
                    "-NoProfile",
                    "-NonInteractive",
                    "-ExecutionPolicy",
                    "Bypass",
                    "-File",
                    helper.toString(),
                    "-Registered",
                    registeredProjectRoot.toString(),
                    "-Project",
                    projectRoot.toString()));
            builder.redirectErrorStream(true);
            builder.redirectOutput(output.toFile());
            Process process = builder.start();
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

            byte[] bytes;
            try (InputStream input = Files.newInputStream(output)) {
                bytes = input.readNBytes(OUTPUT_LIMIT + 1);
            }
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
            return new IdentityPair(registered.group(1).toLowerCase(), project.group(1).toLowerCase());
        } finally {
            Files.deleteIfExists(output);
        }
    }

    private static Path installHelper(Path directory) throws IOException {
        Path target = directory.resolve(HELPER_NAME).toAbsolutePath().normalize();
        Path partial = PrivateLocalStorage.createPrivateTempFile(directory, ".windows-path-identity-", ".ps1");
        try (InputStream input = WindowsPathIdentity.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IOException("packaged Windows path identity helper is missing");
            }
            Files.write(partial, input.readAllBytes(), StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(partial, target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
            }
            PrivateLocalStorage.hardenExistingFile(target);
            return target;
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    private static Path realPath(Path value, String label) throws IOException {
        return Objects.requireNonNull(value, label).toAbsolutePath().normalize().toRealPath();
    }

    private static void requireContained(Path registeredProjectRoot, Path projectRoot) {
        if (!projectRoot.startsWith(registeredProjectRoot)) {
            throw new IllegalStateException(
                    "provider execution path no longer resolves inside the registered project root");
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
        String normalized = Optional.ofNullable(text).orElse("").replace('\r', ' ').replace('\n', ' ').trim();
        if (normalized.length() <= 512) return normalized;
        return normalized.substring(0, 512) + "…";
    }

    private record IdentityPair(String registered, String project) {
    }
}
