package com.minos.adapter.scip.runtime;

import com.minos.adapter.scip.ScipIndexerCatalog;
import com.minos.io.BoundedInputStream;
import com.minos.io.FileTreeOperations;
import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.runtime.BoundedProcessOutput;
import com.minos.runtime.CommandLocator;
import com.minos.runtime.ProviderRuntimeManager;
import com.minos.runtime.ProviderRuntimeStatus;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** M24 runtime extension for C/C++, C#, Go and Rust SCIP providers. */
public final class ManagedPolyglotScipRuntimeManager implements ProviderRuntimeManager {
    private static final Duration INSTALL_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(20);
    private static final String VERSION_MARKER = ".minos-version";
    private static final String SOURCE_MARKER = ".minos-install-source";
    private static final String INTEGRITY_MARKER = ".minos-integrity.sha256";
    private static final String DOTNET_SOURCE = "https://api.nuget.org/v3-flatcontainer";
    private static final String DOTNET_SOURCE_ID = "nuget.org-pinned-nupkg-sha256";
    private static final String DOTNET_PACKAGE_SHA256 = "e2d183fe39b9a56cb8bb2ed2d8b96828fb5434c6db084002bf8a5c6009391b52";
    private static final long MAX_DOTNET_PACKAGE_BYTES = 256L * 1024L * 1024L;
    private static final int MAX_MANAGED_TRAVERSAL_ENTRIES = 50_000;
    private static final int MAX_MANAGED_FILES = 20_000;
    private static final long MAX_MANAGED_BYTES = 512L * 1024L * 1024L;
    private static final String GO_PROXY = "https://proxy.golang.org";
    private static final String GO_SUMDB = "sum.golang.org";
    private static final String GO_SOURCE_ID = "proxy.golang.org+sum.golang.org";

    private final Path home;
    private final Path toolsRoot;

    public ManagedPolyglotScipRuntimeManager(Path minosHome) {
        this.home = Objects.requireNonNull(minosHome, "minosHome").toAbsolutePath().normalize();
        this.toolsRoot = home.resolve("tools");
    }

    @Override
    public List<ProviderRuntimeStatus> list() {
        return List.of(
                inspect(ScipIndexerCatalog.SCIP_CLANG_ID),
                inspect(ScipIndexerCatalog.SCIP_DOTNET_ID),
                inspect(ScipIndexerCatalog.SCIP_GO_ID),
                inspect(ScipIndexerCatalog.RUST_ANALYZER_SCIP_ID));
    }

    @Override
    public ProviderRuntimeStatus inspect(String providerId) {
        ProviderRuntimeStatus status = switch (requireProvider(providerId)) {
            case ScipIndexerCatalog.SCIP_CLANG_ID -> inspectClang();
            case ScipIndexerCatalog.SCIP_DOTNET_ID -> inspectDotnet();
            case ScipIndexerCatalog.SCIP_GO_ID -> inspectGo();
            case ScipIndexerCatalog.RUST_ANALYZER_SCIP_ID -> inspectRust();
            default -> throw new IllegalArgumentException("unsupported polyglot provider: " + providerId);
        };
        return StrongOwnedProcessExecutors.qualifyOwnership(status, home);
    }

    @Override
    public ProviderRuntimeStatus install(String providerId) throws Exception {
        return switch (requireProvider(providerId)) {
            case ScipIndexerCatalog.SCIP_DOTNET_ID -> installDotnet();
            case ScipIndexerCatalog.SCIP_GO_ID -> installGo();
            case ScipIndexerCatalog.SCIP_CLANG_ID -> throw new IllegalStateException(
                    "scip-clang installation is operator-managed in M24; install upstream 0.4.0 on Linux x86_64 and expose it on PATH");
            case ScipIndexerCatalog.RUST_ANALYZER_SCIP_ID -> throw new IllegalStateException(
                    "rust-analyzer installation is operator-managed in M24; MINOS never mutates rustup/toolchains implicitly");
            default -> throw new IllegalArgumentException("unsupported polyglot provider: " + providerId);
        };
    }

    @Override
    public IndexerExecutor executor(String providerId) {
        ProviderRuntimeStatus status = inspect(providerId);
        if (!status.ready()) {
            throw new IllegalStateException("provider runtime is not ready: " + providerId + " -> "
                    + String.join("; ", status.diagnostics()));
        }
        Path executable = status.executable().orElseThrow();
        return switch (providerId) {
            case ScipIndexerCatalog.SCIP_CLANG_ID -> StrongOwnedProcessExecutors.required(
                    providerId, home, new ScipClangProcessPlanFactory(executable));
            case ScipIndexerCatalog.SCIP_DOTNET_ID -> StrongOwnedProcessExecutors.required(
                    providerId, home, new ScipDotnetProcessPlanFactory(executable));
            case ScipIndexerCatalog.SCIP_GO_ID -> StrongOwnedProcessExecutors.required(
                    providerId, home, new ScipGoProcessPlanFactory(executable));
            case ScipIndexerCatalog.RUST_ANALYZER_SCIP_ID -> StrongOwnedProcessExecutors.required(
                    providerId, home, new RustAnalyzerScipProcessPlanFactory(executable));
            default -> throw new IllegalArgumentException("unsupported polyglot provider: " + providerId);
        };
    }

    private ProviderRuntimeStatus inspectClang() {
        if (CommandLocator.isWindows()) {
            return status(ScipIndexerCatalog.SCIP_CLANG_ID, ScipIndexerCatalog.SCIP_CLANG_VERSION,
                    ProviderRuntimeStatus.State.BLOCKED, Optional.empty(),
                    "scip-clang 0.4.0 upstream publishes no Windows binary; M24 runtime qualification is Linux x86_64 only");
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (!os.contains("linux") || !(arch.contains("amd64") || arch.contains("x86_64"))) {
            return status(ScipIndexerCatalog.SCIP_CLANG_ID, ScipIndexerCatalog.SCIP_CLANG_VERSION,
                    ProviderRuntimeStatus.State.BLOCKED, Optional.empty(),
                    "M24 qualifies scip-clang only on Linux x86_64; detected " + os + "/" + arch);
        }
        Optional<Path> executable = CommandLocator.find("scip-clang");
        if (executable.isEmpty()) {
            return status(ScipIndexerCatalog.SCIP_CLANG_ID, ScipIndexerCatalog.SCIP_CLANG_VERSION,
                    ProviderRuntimeStatus.State.NOT_INSTALLED, Optional.empty(),
                    "install upstream scip-clang 0.4.0 and expose scip-clang on PATH");
        }
        Probe probe = probe(CommandLocator.invocation(executable.orElseThrow(), "--version"));
        if (!probe.success() || !probe.output().contains(ScipIndexerCatalog.SCIP_CLANG_VERSION)) {
            return status(ScipIndexerCatalog.SCIP_CLANG_ID, ScipIndexerCatalog.SCIP_CLANG_VERSION,
                    ProviderRuntimeStatus.State.INVALID, executable,
                    "scip-clang version probe must report 0.4.0; output=" + sanitize(probe.output()));
        }
        return status(ScipIndexerCatalog.SCIP_CLANG_ID, ScipIndexerCatalog.SCIP_CLANG_VERSION,
                ProviderRuntimeStatus.State.READY, executable,
                "operator-managed scip-clang 0.4.0 ready on Linux x86_64; compile_commands.json remains project-specific");
    }

    private ProviderRuntimeStatus inspectDotnet() {
        Optional<Path> dotnet = CommandLocator.find("dotnet");
        if (dotnet.isEmpty()) {
            return status(ScipIndexerCatalog.SCIP_DOTNET_ID, ScipIndexerCatalog.SCIP_DOTNET_VERSION,
                    ProviderRuntimeStatus.State.BLOCKED, Optional.empty(),
                    "dotnet is missing from PATH; scip-dotnet 0.2.14 requires .NET SDK 10");
        }
        Probe sdk = probe(CommandLocator.invocation(dotnet.orElseThrow(), "--version"));
        if (!sdk.success() || majorVersion(sdk.output()).orElse(-1) < 10) {
            return status(ScipIndexerCatalog.SCIP_DOTNET_ID, ScipIndexerCatalog.SCIP_DOTNET_VERSION,
                    ProviderRuntimeStatus.State.BLOCKED, Optional.empty(),
                    "scip-dotnet 0.2.14 requires .NET SDK 10+; dotnet --version=" + sanitize(sdk.output()));
        }
        Path directory = dotnetDirectory();
        Path executable = managedExecutable(directory, "scip-dotnet");
        if (!Files.isRegularFile(executable) || !versionMarkerMatches(directory, ScipIndexerCatalog.SCIP_DOTNET_VERSION)) {
            return status(ScipIndexerCatalog.SCIP_DOTNET_ID, ScipIndexerCatalog.SCIP_DOTNET_VERSION,
                    ProviderRuntimeStatus.State.NOT_INSTALLED, Optional.empty(),
                    "managed scip-dotnet 0.2.14 is not installed under " + directory);
        }
        if (!installSourceMatches(directory, DOTNET_SOURCE_ID) || !integrityManifestMatches(directory)) {
            return status(ScipIndexerCatalog.SCIP_DOTNET_ID, ScipIndexerCatalog.SCIP_DOTNET_VERSION,
                    ProviderRuntimeStatus.State.INVALID, Optional.of(executable),
                    "managed scip-dotnet installation provenance/integrity verification failed");
        }
        return status(ScipIndexerCatalog.SCIP_DOTNET_ID, ScipIndexerCatalog.SCIP_DOTNET_VERSION,
                ProviderRuntimeStatus.State.READY, Optional.of(executable),
                "managed scip-dotnet 0.2.14 ready; primary nupkg SHA-256 pinned and local integrity manifest verified; dotnet SDK="
                        + sanitize(sdk.output()));
    }

    private ProviderRuntimeStatus inspectGo() {
        Optional<Path> go = CommandLocator.find("go");
        if (go.isEmpty()) {
            return status(ScipIndexerCatalog.SCIP_GO_ID, ScipIndexerCatalog.SCIP_GO_VERSION,
                    ProviderRuntimeStatus.State.BLOCKED, Optional.empty(),
                    "go is missing from PATH; scip-go 0.2.7 requires a Go toolchain");
        }
        Probe goVersion = probe(CommandLocator.invocation(go.orElseThrow(), "version"));
        if (!goVersion.success()) {
            return status(ScipIndexerCatalog.SCIP_GO_ID, ScipIndexerCatalog.SCIP_GO_VERSION,
                    ProviderRuntimeStatus.State.BLOCKED, Optional.empty(),
                    "go version probe failed: " + sanitize(goVersion.output()));
        }
        Path directory = goDirectory();
        Path executable = managedExecutable(directory, "scip-go");
        if (!Files.isRegularFile(executable) || !versionMarkerMatches(directory, ScipIndexerCatalog.SCIP_GO_VERSION)) {
            return status(ScipIndexerCatalog.SCIP_GO_ID, ScipIndexerCatalog.SCIP_GO_VERSION,
                    ProviderRuntimeStatus.State.NOT_INSTALLED, Optional.empty(),
                    "managed scip-go 0.2.7 is not installed under " + directory);
        }
        if (!installSourceMatches(directory, GO_SOURCE_ID) || !integrityManifestMatches(directory)) {
            return status(ScipIndexerCatalog.SCIP_GO_ID, ScipIndexerCatalog.SCIP_GO_VERSION,
                    ProviderRuntimeStatus.State.INVALID, Optional.of(executable),
                    "managed scip-go installation provenance/integrity verification failed");
        }
        return status(ScipIndexerCatalog.SCIP_GO_ID, ScipIndexerCatalog.SCIP_GO_VERSION,
                ProviderRuntimeStatus.State.READY, Optional.of(executable),
                "managed scip-go 0.2.7 ready; proxy.golang.org + sum.golang.org enforced and local integrity manifest verified; "
                        + sanitize(goVersion.output()));
    }

    private ProviderRuntimeStatus inspectRust() {
        Optional<Path> cargo = CommandLocator.find("cargo");
        Optional<Path> rustc = CommandLocator.find("rustc");
        Optional<Path> analyzer = CommandLocator.find("rust-analyzer");
        List<String> missing = new ArrayList<>();
        if (cargo.isEmpty()) missing.add("cargo");
        if (rustc.isEmpty()) missing.add("rustc");
        if (analyzer.isEmpty()) missing.add("rust-analyzer");
        if (!missing.isEmpty()) {
            return status(ScipIndexerCatalog.RUST_ANALYZER_SCIP_ID, ScipIndexerCatalog.RUST_ANALYZER_SCIP_VERSION,
                    ProviderRuntimeStatus.State.BLOCKED, Optional.empty(),
                    "missing Rust runtime requirements: " + String.join(", ", missing));
        }
        Path executable = analyzer.orElseThrow();
        Probe probe = probe(CommandLocator.invocation(executable, "--version"));
        String output = probe.output();
        if (!probe.success() || !output.contains(ScipIndexerCatalog.RUST_ANALYZER_SCIP_VERSION)) {
            return status(ScipIndexerCatalog.RUST_ANALYZER_SCIP_ID, ScipIndexerCatalog.RUST_ANALYZER_SCIP_VERSION,
                    ProviderRuntimeStatus.State.INVALID, Optional.of(executable),
                    "rust-analyzer version probe must report " + ScipIndexerCatalog.RUST_ANALYZER_SCIP_VERSION
                            + "; output=" + sanitize(output));
        }
        return status(ScipIndexerCatalog.RUST_ANALYZER_SCIP_ID, ScipIndexerCatalog.RUST_ANALYZER_SCIP_VERSION,
                ProviderRuntimeStatus.State.READY, Optional.of(executable),
                "operator-managed rust-analyzer v" + ScipIndexerCatalog.RUST_ANALYZER_SCIP_VERSION
                        + " ready; artifact provenance release " + ScipIndexerCatalog.RUST_ANALYZER_SCIP_RELEASE
                        + " / commit " + ScipIndexerCatalog.RUST_ANALYZER_SCIP_COMMIT + "; cargo/rustc present");
    }

    private ProviderRuntimeStatus installDotnet() throws Exception {
        Path dotnet = CommandLocator.find("dotnet").orElseThrow(() -> new IllegalStateException(
                "dotnet is missing from PATH; install .NET SDK 10+ before scip-dotnet"));
        Probe sdk = probe(CommandLocator.invocation(dotnet, "--version"));
        if (!sdk.success() || majorVersion(sdk.output()).orElse(-1) < 10) {
            throw new IllegalStateException("scip-dotnet 0.2.14 requires .NET SDK 10+; dotnet --version=" + sanitize(sdk.output()));
        }
        Path destination = dotnetDirectory();
        Path partial = destination.resolveSibling(destination.getFileName() + ".partial");
        deleteRecursively(partial);
        Files.createDirectories(partial);
        Path localSource = partial.resolve("pinned-nuget-source");
        Files.createDirectories(localSource);
        Path pinnedPackage = localSource.resolve("scip-dotnet." + ScipIndexerCatalog.SCIP_DOTNET_VERSION + ".nupkg");
        downloadPinnedDotnetPackage(pinnedPackage);
        Path nugetConfig = partial.resolve("minos-nuget.config");
        Files.writeString(nugetConfig,
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                        + "<configuration><packageSources><clear/>"
                        + "<add key=\"minos-pinned\" value=\"" + xml(localSource.toString()) + "\"/>"
                        + "</packageSources></configuration>\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        CommandResult result = run(CommandLocator.invocation(
                        dotnet, "tool", "install", "--tool-path", partial.toString(), "scip-dotnet",
                        "--version", ScipIndexerCatalog.SCIP_DOTNET_VERSION,
                        "--configfile", nugetConfig.toString(), "--no-cache", "--ignore-failed-sources"),
                home, INSTALL_TIMEOUT, Map.of());
        Files.deleteIfExists(nugetConfig);
        deleteRecursively(localSource);
        if (!result.success()) {
            deleteRecursively(partial);
            throw new IllegalStateException("scip-dotnet install failed: " + sanitize(result.output()));
        }
        Path executable = managedExecutable(partial, "scip-dotnet");
        if (!Files.isRegularFile(executable)) {
            deleteRecursively(partial);
            throw new IllegalStateException("scip-dotnet installation completed without executable: " + executable);
        }
        writeManagedMarkers(partial, ScipIndexerCatalog.SCIP_DOTNET_VERSION, DOTNET_SOURCE_ID);
        replaceDirectory(partial, destination);
        return inspectDotnet();
    }

    private ProviderRuntimeStatus installGo() throws Exception {
        Path go = CommandLocator.find("go").orElseThrow(() -> new IllegalStateException(
                "go is missing from PATH; install a Go toolchain before scip-go"));
        Path destination = goDirectory();
        Path partial = destination.resolveSibling(destination.getFileName() + ".partial");
        deleteRecursively(partial);
        Files.createDirectories(partial);
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("GOBIN", partial.toString());
        environment.put("GOPROXY", GO_PROXY);
        environment.put("GOSUMDB", GO_SUMDB);
        environment.put("GONOSUMDB", "");
        environment.put("GOPRIVATE", "");
        environment.put("GONOPROXY", "");
        CommandResult result = run(CommandLocator.invocation(
                        go, "install", "github.com/scip-code/scip-go/cmd/scip-go@v" + ScipIndexerCatalog.SCIP_GO_VERSION),
                home, INSTALL_TIMEOUT, environment);
        if (!result.success()) {
            deleteRecursively(partial);
            throw new IllegalStateException("scip-go install failed: " + sanitize(result.output()));
        }
        Path executable = managedExecutable(partial, "scip-go");
        if (!Files.isRegularFile(executable)) {
            deleteRecursively(partial);
            throw new IllegalStateException("scip-go installation completed without executable: " + executable);
        }
        writeManagedMarkers(partial, ScipIndexerCatalog.SCIP_GO_VERSION, GO_SOURCE_ID);
        replaceDirectory(partial, destination);
        return inspectGo();
    }

    private static void downloadPinnedDotnetPackage(Path target) throws IOException, InterruptedException {
        String version = ScipIndexerCatalog.SCIP_DOTNET_VERSION.toLowerCase(Locale.ROOT);
        URI uri = URI.create(DOTNET_SOURCE + "/scip-dotnet/" + version + "/scip-dotnet." + version + ".nupkg");
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(PROBE_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(INSTALL_TIMEOUT).GET().build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            try (InputStream ignored = response.body()) { }
            throw new IOException("scip-dotnet pinned nupkg download failed with HTTP " + response.statusCode());
        }
        MessageDigest digest = sha256Digest();
        long total = 0L;
        try (InputStream input = response.body(); OutputStream output = Files.newOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                try { total = Math.addExact(total, read); }
                catch (ArithmeticException exception) { throw new IOException("scip-dotnet nupkg byte counter overflow", exception); }
                if (total > MAX_DOTNET_PACKAGE_BYTES) throw new IOException("scip-dotnet nupkg exceeds byte limit");
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        } catch (Exception exception) {
            Files.deleteIfExists(target);
            throw exception;
        }
        String actual = HexFormat.of().formatHex(digest.digest());
        if (!DOTNET_PACKAGE_SHA256.equals(actual)) {
            Files.deleteIfExists(target);
            throw new IOException("scip-dotnet nupkg SHA-256 mismatch: " + actual);
        }
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;")
                .replace("<", "&lt;").replace(">", "&gt;");
    }

    private Path dotnetDirectory() {
        return toolsRoot.resolve(ScipIndexerCatalog.SCIP_DOTNET_ID).resolve(ScipIndexerCatalog.SCIP_DOTNET_VERSION);
    }

    private Path goDirectory() {
        return toolsRoot.resolve(ScipIndexerCatalog.SCIP_GO_ID).resolve(ScipIndexerCatalog.SCIP_GO_VERSION);
    }

    private static Path managedExecutable(Path directory, String basename) {
        return directory.resolve(CommandLocator.isWindows() ? basename + ".exe" : basename);
    }

    private static void writeManagedMarkers(Path directory, String version, String source) throws IOException {
        Files.writeString(directory.resolve(VERSION_MARKER), version, StandardCharsets.UTF_8);
        Files.writeString(directory.resolve(SOURCE_MARKER), source, StandardCharsets.UTF_8);
        Files.writeString(directory.resolve(INTEGRITY_MARKER), directoryDigest(directory), StandardCharsets.UTF_8);
    }

    private static boolean versionMarkerMatches(Path directory, String expected) {
        return markerMatches(directory.resolve(VERSION_MARKER), expected);
    }

    private static boolean installSourceMatches(Path directory, String expected) {
        return markerMatches(directory.resolve(SOURCE_MARKER), expected);
    }

    private static boolean markerMatches(Path marker, String expected) {
        try {
            return Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(marker)
                    && expected.equals(readBoundedText(marker, 4L * 1024L, "managed runtime marker").trim());
        } catch (IOException exception) {
            return false;
        }
    }

    private static boolean integrityManifestMatches(Path directory) {
        Path marker = directory.resolve(INTEGRITY_MARKER);
        try {
            return Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(marker)
                    && readBoundedText(marker, 4L * 1024L, "managed runtime integrity marker")
                            .trim().equals(directoryDigest(directory));
        } catch (IOException exception) {
            return false;
        }
    }

    private static String directoryDigest(Path directory) throws IOException {
        MessageDigest digest = sha256Digest();
        List<Path> files = new ArrayList<>();
        int traversed = 0;
        try (var paths = Files.walk(directory)) {
            var iterator = paths.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                traversed++;
                if (traversed > MAX_MANAGED_TRAVERSAL_ENTRIES) {
                    throw new IOException("managed runtime integrity traversal exceeds entry limit");
                }
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isSymbolicLink(path)
                        && !path.getFileName().toString().equals(INTEGRITY_MARKER)) {
                    if (files.size() >= MAX_MANAGED_FILES) {
                        throw new IOException("managed runtime integrity traversal exceeds file limit");
                    }
                    files.add(path);
                }
            }
        }
        files.sort(Comparator.comparing(path -> portable(directory.relativize(path))));
        long totalBytes = 0L;
        for (Path file : files) {
            String relative = portable(directory.relativize(file));
            digest.update(relative.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            long expected = Files.size(file);
            if (expected < 0L || expected > MAX_MANAGED_BYTES - totalBytes) {
                throw new IOException("managed runtime integrity traversal exceeds byte limit");
            }
            long observed = 0L;
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read == 0) continue;
                    observed += read;
                    if (observed > expected) throw new IOException("managed runtime file grew during integrity scan: " + relative);
                    digest.update(buffer, 0, read);
                }
            }
            if (observed != expected) throw new IOException("managed runtime file changed during integrity scan: " + relative);
            totalBytes += observed;
            digest.update((byte) 0xff);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String portable(Path path) { return path.toString().replace('\\', '/'); }

    private static MessageDigest sha256Digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 is unavailable", exception); }
    }

    private static Optional<Integer> majorVersion(String output) {
        if (output == null) return Optional.empty();
        String normalized = output.trim();
        int separator = normalized.indexOf('.');
        String major = separator < 0 ? normalized : normalized.substring(0, separator);
        try { return Optional.of(Integer.parseInt(major)); }
        catch (NumberFormatException exception) { return Optional.empty(); }
    }

    private Probe probe(List<String> command) {
        try {
            CommandResult result = run(command, home, PROBE_TIMEOUT, Map.of());
            return new Probe(result.success(), result.output());
        } catch (Exception exception) {
            return new Probe(false, exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage());
        }
    }

    private static CommandResult run(
            List<String> command,
            Path workingDirectory,
            Duration timeout,
            Map<String, String> environmentOverrides
    ) throws Exception {
        Path outputFile = Files.createTempFile(workingDirectory.toAbsolutePath().normalize(), "minos-m24-command-", ".log");
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(workingDirectory.toAbsolutePath().normalize().toFile());
            builder.redirectErrorStream(true);
            builder.environment().putAll(Objects.requireNonNull(environmentOverrides, "environmentOverrides"));
            Process process = builder.start();
            BoundedProcessOutput.Capture capture = BoundedProcessOutput.capture(process, outputFile, null);
            boolean completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                terminate(process);
                capture.await();
                return new CommandResult(false, "command timed out after " + timeout + "; " + readOutput(outputFile));
            }
            capture.await();
            return new CommandResult(process.exitValue() == 0, readOutput(outputFile));
        } finally {
            Files.deleteIfExists(outputFile);
        }
    }

    private static void terminate(Process process) throws InterruptedException {
        process.descendants().toList().reversed().forEach(handle -> {
            if (handle.isAlive()) handle.destroyForcibly();
        });
        if (process.isAlive()) process.destroyForcibly();
        process.waitFor(5, TimeUnit.SECONDS);
    }

    private static String readOutput(Path outputFile) throws IOException {
        return Files.isRegularFile(outputFile, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(outputFile)
                ? readBoundedText(outputFile, BoundedProcessOutput.DEFAULT_MAX_BYTES_PER_STREAM,
                        "managed runtime command output")
                : "";
    }

    private static String readBoundedText(Path file, long maximumBytes, String boundary) throws IOException {
        try (BoundedInputStream input = new BoundedInputStream(Files.newInputStream(file), maximumBytes, boundary)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void replaceDirectory(Path source, Path destination) throws IOException {
        deleteRecursively(destination);
        Files.createDirectories(destination.getParent());
        try { Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE); }
        catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        FileTreeOperations.deleteRecursively(root);
    }

    private static ProviderRuntimeStatus status(
            String providerId, String version, ProviderRuntimeStatus.State state,
            Optional<Path> executable, String diagnostic) {
        return new ProviderRuntimeStatus(providerId, version, state, executable, List.of(diagnostic), false);
    }

    private static String requireProvider(String providerId) {
        if (providerId == null || providerId.isBlank()) throw new IllegalArgumentException("providerId must not be blank");
        return providerId;
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) return "<empty>";
        String singleLine = value.replace('\r', ' ').replace('\n', ' ').trim();
        return singleLine.length() <= 512 ? singleLine : singleLine.substring(0, 512) + "...";
    }

    private record Probe(boolean success, String output) {}
    private record CommandResult(boolean success, String output) {}
}
