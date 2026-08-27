package com.minos.adapter.scip.runtime;

import com.minos.io.BoundedInputStream;
import com.minos.io.BoundedLineReader;
import com.minos.io.FileTreeOperations;
import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.runtime.BoundedProcessOutput;
import com.minos.runtime.CommandLocator;
import com.minos.runtime.ProviderRuntimeManager;
import com.minos.runtime.ProviderRuntimeStatus;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Local manager for MINOS-qualified SCIP runtimes. */
public final class ManagedScipProviderRuntimeManager implements ProviderRuntimeManager {

    private static final int MAX_INSTALL_LOG_LINE_CHARS = 64 * 1024;
    private static final String NODE_MODULES_DIR = "node_modules";
    private static final String USER_AGENT_HEADER = "User-Agent";
    private static final String MINOS_USER_AGENT = "MINOS-Code-Intelligence";

    public static final String SCIP_TYPESCRIPT_ID = "scip-typescript";
    public static final String SCIP_TYPESCRIPT_VERSION = "0.4.0";
    public static final String SCIP_JAVA_ID = "scip-java";
    public static final String SCIP_JAVA_VERSION = "0.13.1";
    public static final String SCIP_JAVA_COORDINATE = "org.scip-code:scip-java:" + SCIP_JAVA_VERSION;
    static final String SCIP_JAVA_MAIN_CLASS = "org.scip_code.scip_java.ScipJava";

    private static final String COURSIER_LAUNCHERS_COMMIT = "15f36c167c30be237105f923151adaf177e7ee61";
    private static final String COURSIER_LAUNCHER_ID = "windows-x64-" + COURSIER_LAUNCHERS_COMMIT.substring(0, 12);
    private static final String COURSIER_WINDOWS_SHA256 = "d6b375ea3f1c58312912af96260cca0c975bc873dc430820e2d67d50b294be3a";
    private static final long MAX_COURSIER_ARCHIVE_BYTES = 64L * 1024L * 1024L;
    private static final URI COURSIER_WINDOWS_URI = URI.create(
            "https://raw.githubusercontent.com/coursier/launchers/" + COURSIER_LAUNCHERS_COMMIT + "/cs-x86_64-pc-win32.zip");

    // A project's own Maven wrapper or a host-installed `mvn` cannot be reached by the Windows
    // AppContainer sandbox in general: wrapper discovery walks ancestor directories the sandbox
    // never grants, and a host `mvn` may sit anywhere PATH names, most of which carry no MINOS
    // grant either. scip-java on Windows therefore gets its own MINOS-managed Maven, mirroring how
    // Coursier and scip-typescript are already fetched, checksummed and confined to MINOS_HOME/tools
    // — a location the sandbox already grants through the existing managed-tools root.
    private static final String MAVEN_VERSION = "3.9.16";
    private static final String MAVEN_SHA256 = "5af3b743dd8b876b5c45da33b676251e5f1687712644abb4ee519ca56e1d89ce";
    private static final long MAX_MAVEN_ARCHIVE_BYTES = 16L * 1024L * 1024L;
    private static final long MAX_MAVEN_ARCHIVE_ENTRIES = 4_096L;
    private static final long MAX_MAVEN_EXTRACTED_BYTES = 64L * 1024L * 1024L;
    private static final URI MAVEN_DISTRIBUTION_URI = URI.create(
            "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/" + MAVEN_VERSION
                    + "/apache-maven-" + MAVEN_VERSION + "-bin.zip");
    private static final String SCIP_TYPESCRIPT_NPM_LOCK_RESOURCE = "scip-typescript-package-lock.json";
    private static final String SCIP_TYPESCRIPT_NPM_INTEGRITY = "sha512-k+AtsrqmS41Sd5qjkZlHcmvoSQIvBOonRj4jpgp0KNFM6aqvMGpdSuPUqrUcg8ENTKjUbfaUVszgQwq3bCOvwA==";

    // A host `node` resolved from PATH can sit anywhere -- typically under Program Files or a
    // per-user npm install -- none of which the non-elevated Windows AppContainer sandbox can grant
    // access to. scip-typescript.cmd's own npm-generated shim resolves `node` via PATH internally,
    // which the sandbox's static command-line ACL computation can never see. scip-typescript on
    // Windows therefore gets its own MINOS-managed, portable Node.js, invoked directly against the
    // package's entry script (bypassing the cmd.exe shim, and cmd.exe entirely) so both the
    // interpreter and the script are ordinary, sandbox-grantable paths under MINOS_HOME/tools.
    private static final String NODE_VERSION = "24.20.0";
    private static final String NODE_DISTRIBUTION_ID = "node-v" + NODE_VERSION + "-win-x64";
    private static final String NODE_SHA256 = "6cac9ffbca8f6a47091e4b5c772e0606049c3871cb67d900c0cedde630e545ba";
    private static final long MAX_NODE_ARCHIVE_BYTES = 128L * 1024L * 1024L;
    private static final long MAX_NODE_ARCHIVE_ENTRIES = 8_192L;
    private static final long MAX_NODE_EXTRACTED_BYTES = 256L * 1024L * 1024L;
    private static final URI NODE_DISTRIBUTION_URI = URI.create(
            "https://nodejs.org/dist/v" + NODE_VERSION + "/" + NODE_DISTRIBUTION_ID + ".zip");
    private static final String WINDOWS_RUNNER_RESOURCE = "scip-java-windows-runner.ps1";
    private static final String WINDOWS_PATCH_RESOURCE = "ScipWriter.java";

    private final Path home;
    private final Path toolsRoot;

    public ManagedScipProviderRuntimeManager(Path minosHome) {
        this.home = Objects.requireNonNull(minosHome, "minosHome").toAbsolutePath().normalize();
        this.toolsRoot = home.resolve("tools");
    }

    @Override public List<ProviderRuntimeStatus> list() { return List.of(inspect(SCIP_JAVA_ID), inspect(SCIP_TYPESCRIPT_ID)); }

    @Override
    public ProviderRuntimeStatus inspect(String providerId) {
        ProviderRuntimeStatus status = switch (providerId) {
            case SCIP_TYPESCRIPT_ID -> inspectTypeScript();
            case SCIP_JAVA_ID -> inspectJava();
            default -> throw new IllegalArgumentException("unknown managed provider: " + providerId);
        };
        return StrongOwnedProcessExecutors.qualifyOwnership(status, home);
    }

    @Override
    public ProviderRuntimeStatus install(String providerId) throws Exception {
        return switch (providerId) {
            case SCIP_TYPESCRIPT_ID -> installTypeScript();
            case SCIP_JAVA_ID -> installJava();
            default -> throw new IllegalArgumentException("unknown managed provider: " + providerId);
        };
    }

    @Override
    public IndexerExecutor executor(String providerId) {
        ProviderRuntimeStatus status = inspect(providerId);
        if (!status.ready() || status.executable().isEmpty()) {
            throw new IllegalStateException("provider runtime is not ready: " + providerId + " — "
                    + String.join("; ", status.diagnostics()));
        }
        return switch (providerId) {
            case SCIP_TYPESCRIPT_ID -> StrongOwnedProcessExecutors.required(
                    providerId, home, CommandLocator.isWindows()
                            ? new ScipTypeScriptProcessPlanFactory(status.executable().orElseThrow(), typeScriptMainScript())
                            : new ScipTypeScriptProcessPlanFactory(status.executable().orElseThrow()));
            case SCIP_JAVA_ID -> StrongOwnedProcessExecutors.required(
                    providerId, home, new ScipJavaProcessPlanFactory(
                            status.executable().orElseThrow(), SCIP_JAVA_COORDINATE, scipJavaWindowsRunner(),
                            CommandLocator.isWindows() ? mavenExecutable() : null));
            default -> throw new IllegalArgumentException("unknown managed provider: " + providerId);
        };
    }

    private ProviderRuntimeStatus inspectTypeScript() {
        List<String> diagnostics = new ArrayList<>();
        boolean packageInstalled = Files.isRegularFile(typeScriptMainScript());
        if (!packageInstalled) diagnostics.add("managed scip-typescript " + SCIP_TYPESCRIPT_VERSION + " is not installed");

        Path executable;
        if (CommandLocator.isWindows()) {
            executable = nodeExecutable();
            if (!Files.isRegularFile(executable)) {
                diagnostics.add("managed Node.js " + NODE_VERSION + " runtime is not installed in MINOS_HOME/tools");
            }
        } else {
            Optional<Path> node = CommandLocator.find("node");
            Optional<Path> npm = CommandLocator.find("npm");
            if (node.isEmpty()) diagnostics.add("Node.js is not available in PATH");
            if (npm.isEmpty()) diagnostics.add("npm is not available in PATH");
            executable = typeScriptExecutable();
        }
        ProviderRuntimeStatus.State state;
        if (diagnostics.isEmpty()) {
            state = ProviderRuntimeStatus.State.READY;
        } else if (packageInstalled) {
            state = ProviderRuntimeStatus.State.BLOCKED;
        } else {
            state = ProviderRuntimeStatus.State.NOT_INSTALLED;
        }
        return new ProviderRuntimeStatus(
                SCIP_TYPESCRIPT_ID, SCIP_TYPESCRIPT_VERSION, state,
                Files.isRegularFile(executable) ? Optional.of(executable) : Optional.empty(), diagnostics);
    }

    private ProviderRuntimeStatus inspectJava() {
        List<String> diagnostics = new ArrayList<>();
        Optional<Path> coursier = coursierExecutable();
        boolean windowsRuntimeInstalled = !CommandLocator.isWindows() || windowsRuntimeInstalled();
        boolean mavenInstalled = !CommandLocator.isWindows() || Files.isRegularFile(mavenExecutable());
        if (coursier.isEmpty()) diagnostics.add("Coursier is not installed in MINOS_HOME/tools and was not found in PATH");
        if (!windowsRuntimeInstalled) diagnostics.add("managed scip-java " + SCIP_JAVA_VERSION + " Windows compatibility runtime is not installed");
        if (!mavenInstalled) diagnostics.add("managed Maven " + MAVEN_VERSION + " is not installed in MINOS_HOME/tools");
        if (CommandLocator.isWindows()) {
            if (powerShellExecutable().isEmpty()) diagnostics.add("PowerShell (powershell.exe or pwsh.exe) is required for scip-java on Windows");
            if (!gitBashAvailable()) diagnostics.add("Git Bash (bash.exe) is required for scip-java on Windows");
            if (!cSharpCompilerAvailable()) diagnostics.add("csc.exe is required to build scip-java Windows command shims");
        }
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome == null || javaHome.isBlank()) {
            diagnostics.add("JAVA_HOME is not set to the project JDK");
        } else {
            Path javac = Path.of(javaHome).resolve("bin").resolve(CommandLocator.isWindows() ? "javac.exe" : "javac");
            if (!Files.isRegularFile(javac)) diagnostics.add("JAVA_HOME does not contain javac: " + javaHome);
        }
        boolean installed = coursier.isPresent() && windowsRuntimeInstalled && mavenInstalled;
        ProviderRuntimeStatus.State state = !installed
                ? ProviderRuntimeStatus.State.NOT_INSTALLED
                : diagnostics.isEmpty() ? ProviderRuntimeStatus.State.READY : ProviderRuntimeStatus.State.BLOCKED;
        return new ProviderRuntimeStatus(SCIP_JAVA_ID, SCIP_JAVA_VERSION, state, coursier, diagnostics);
    }

    private ProviderRuntimeStatus installTypeScript() throws Exception {
        Path npm = CommandLocator.find("npm")
                .orElseThrow(() -> new IllegalStateException("npm is required to install scip-typescript"));
        CommandLocator.find("node").orElseThrow(() -> new IllegalStateException("Node.js is required to run scip-typescript"));
        Files.createDirectories(toolsRoot);
        if (CommandLocator.isWindows()) {
            ensureNode();
        }
        Path destination = typeScriptRoot();
        Path partial = destination.resolveSibling(destination.getFileName() + ".partial");
        deleteRecursively(partial);
        Files.createDirectories(partial);
        try {
            LockedNpmPackage.prepare(
                    ManagedScipProviderRuntimeManager.class,
                    partial,
                    SCIP_TYPESCRIPT_NPM_LOCK_RESOURCE,
                    "@sourcegraph/scip-typescript",
                    SCIP_TYPESCRIPT_VERSION,
                    SCIP_TYPESCRIPT_NPM_INTEGRITY);
            run(CommandLocator.invocation(
                    npm, "ci", "--prefix", partial.toString(), "--no-audit", "--no-fund", "--ignore-scripts"),
                    home, toolsRoot.resolve("scip-typescript-install.log"), Duration.ofMinutes(10));
            Path installed = partial.resolve(NODE_MODULES_DIR).resolve(".bin")
                    .resolve(CommandLocator.isWindows() ? "scip-typescript.cmd" : "scip-typescript");
            if (!Files.isRegularFile(installed)) throw new IllegalStateException("scip-typescript executable was not created: " + installed);
            deleteRecursively(destination);
            move(partial, destination);
        } finally {
            deleteRecursively(partial);
        }
        ProviderRuntimeStatus status = inspectTypeScript();
        if (!status.ready()) throw new IllegalStateException("scip-typescript installation is incomplete: " + String.join("; ", status.diagnostics()));
        return status;
    }

    private ProviderRuntimeStatus installJava() throws Exception {
        Files.createDirectories(toolsRoot);
        Path coursier = ensureCoursier();
        if (CommandLocator.isWindows()) {
            installJavaWindowsRuntime();
            ensureMaven();
        }
        run(List.of(coursier.toString(), "--help"), home, toolsRoot.resolve("coursier-verify.log"), Duration.ofMinutes(1));
        Path scipJavaLog = toolsRoot.resolve("scip-java-install.log");
        run(scipJavaInstallationProbe(coursier), home, scipJavaLog, Duration.ofMinutes(10));
        requireExpectedScipJavaVersion(scipJavaLog);
        ProviderRuntimeStatus status = inspectJava();
        if (!status.ready()) throw new IllegalStateException("scip-java installation is incomplete: " + String.join("; ", status.diagnostics()));
        return status;
    }

    static List<String> scipJavaInstallationProbe(Path coursier) {
        return List.of(coursier.toString(), "launch", SCIP_JAVA_COORDINATE, "--jvm", "system",
                "--main", SCIP_JAVA_MAIN_CLASS, "--", "--version");
    }

    static void requireExpectedScipJavaVersion(Path log) throws IOException {
        String expected = "scip-java version " + SCIP_JAVA_VERSION;
        if (!Files.isRegularFile(log, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(log)) {
            throw new IOException("scip-java version log must be a regular non-symbolic file: " + log);
        }
        boolean found = false;
        try (BoundedInputStream input = new BoundedInputStream(
                     Files.newInputStream(log), BoundedProcessOutput.DEFAULT_MAX_BYTES_PER_STREAM,
                     "scip-java installation log");
             BoundedLineReader reader = new BoundedLineReader(
                     new InputStreamReader(input, StandardCharsets.UTF_8), MAX_INSTALL_LOG_LINE_CHARS)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (expected.equals(line.trim())) {
                    found = true;
                    break;
                }
            }
        }
        if (!found) throw new IllegalStateException("scip-java version verification failed; expected `" + expected + "`; see " + log);
    }

    private void installJavaWindowsRuntime() throws IOException {
        Path runtime = scipJavaRuntimeRoot();
        Files.createDirectories(runtime);
        copyPackagedResource(WINDOWS_RUNNER_RESOURCE, runtime.resolve(WINDOWS_RUNNER_RESOURCE));
        copyPackagedResource(WINDOWS_PATCH_RESOURCE, runtime.resolve(WINDOWS_PATCH_RESOURCE));
    }

    private void copyPackagedResource(String name, Path destination) throws IOException {
        try (InputStream input = ManagedScipProviderRuntimeManager.class.getResourceAsStream(name)) {
            if (input == null) throw new IllegalStateException("packaged scip-java runtime resource is missing: " + name);
            Path partial = destination.resolveSibling(destination.getFileName() + ".partial");
            Files.deleteIfExists(partial);
            Files.copy(input, partial, StandardCopyOption.REPLACE_EXISTING);
            move(partial, destination);
        }
    }

    private Path ensureCoursier() throws Exception {
        Optional<Path> existing = coursierExecutable();
        if (existing.isPresent()) return existing.orElseThrow();
        if (!CommandLocator.isWindows()) {
            throw new IllegalStateException("automatic Coursier installation is currently packaged for Windows x64; install `cs` in PATH");
        }
        Path directory = toolsRoot.resolve("coursier").resolve(COURSIER_LAUNCHER_ID);
        Files.createDirectories(directory);
        Path destination = directory.resolve("cs.exe");
        Path archive = directory.resolve("cs-x86_64-pc-win32.zip");
        Path archivePartial = directory.resolve("cs-x86_64-pc-win32.partial.zip");
        Path executablePartial = directory.resolve("cs.partial.exe");
        Files.deleteIfExists(archivePartial);
        Files.deleteIfExists(executablePartial);

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30)).build();
        HttpRequest request = HttpRequest.newBuilder(COURSIER_WINDOWS_URI)
                .timeout(Duration.ofMinutes(2))
                .header(USER_AGENT_HEADER, MINOS_USER_AGENT).build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            try (InputStream ignored = response.body()) { /* close error response */ }
            throw new IllegalStateException("Coursier launcher download failed with HTTP " + response.statusCode());
        }
        try (InputStream responseBody = response.body();
             BoundedInputStream bounded = new BoundedInputStream(
                     responseBody, MAX_COURSIER_ARCHIVE_BYTES, "Coursier launcher archive");
             OutputStream output = Files.newOutputStream(archivePartial)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = bounded.read(buffer)) >= 0) if (read > 0) output.write(buffer, 0, read);
        } catch (Exception exception) {
            Files.deleteIfExists(archivePartial);
            throw exception;
        }
        if (!Files.isRegularFile(archivePartial, LinkOption.NOFOLLOW_LINKS) || Files.size(archivePartial) == 0L) {
            Files.deleteIfExists(archivePartial);
            throw new IllegalStateException("Coursier launcher download produced an empty archive");
        }
        String actualDigest = sha256(archivePartial);
        if (!COURSIER_WINDOWS_SHA256.equals(actualDigest)) {
            Files.deleteIfExists(archivePartial);
            throw new IllegalStateException("Coursier launcher checksum mismatch: expected="
                    + COURSIER_WINDOWS_SHA256 + " actual=" + actualDigest);
        }
        move(archivePartial, archive);

        int executableEntries = 0;
        try (InputStream input = Files.newInputStream(archive); ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory() && entry.getName().toLowerCase().endsWith(".exe")) {
                    executableEntries++;
                    if (executableEntries == 1) Files.copy(zip, executablePartial, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        if (executableEntries != 1 || !Files.isRegularFile(executablePartial) || Files.size(executablePartial) == 0L) {
            Files.deleteIfExists(executablePartial);
            throw new IllegalStateException("Coursier launcher ZIP did not contain a Windows executable");
        }
        move(executablePartial, destination);
        return destination;
    }

    private Path mavenRoot() { return toolsRoot.resolve("maven").resolve(MAVEN_VERSION); }

    Path mavenExecutable() {
        return mavenRoot().resolve("apache-maven-" + MAVEN_VERSION).resolve("bin")
                .resolve(CommandLocator.isWindows() ? "mvn.cmd" : "mvn");
    }

    private Path ensureMaven() throws Exception {
        Path existing = mavenExecutable();
        if (Files.isRegularFile(existing)) return existing;
        Path root = mavenRoot();
        Files.createDirectories(root);
        Path archive = root.resolve("apache-maven-" + MAVEN_VERSION + "-bin.zip");
        Path archivePartial = root.resolve("apache-maven-" + MAVEN_VERSION + "-bin.partial.zip");
        Files.deleteIfExists(archivePartial);

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30)).build();
        HttpRequest request = HttpRequest.newBuilder(MAVEN_DISTRIBUTION_URI)
                .timeout(Duration.ofMinutes(3))
                .header(USER_AGENT_HEADER, MINOS_USER_AGENT).build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            try (InputStream ignored = response.body()) { /* close error response */ }
            throw new IllegalStateException("Maven distribution download failed with HTTP " + response.statusCode());
        }
        try (InputStream responseBody = response.body();
             BoundedInputStream bounded = new BoundedInputStream(
                     responseBody, MAX_MAVEN_ARCHIVE_BYTES, "Maven distribution archive");
             OutputStream output = Files.newOutputStream(archivePartial)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = bounded.read(buffer)) >= 0) if (read > 0) output.write(buffer, 0, read);
        } catch (Exception exception) {
            Files.deleteIfExists(archivePartial);
            throw exception;
        }
        if (!Files.isRegularFile(archivePartial, LinkOption.NOFOLLOW_LINKS) || Files.size(archivePartial) == 0L) {
            Files.deleteIfExists(archivePartial);
            throw new IllegalStateException("Maven distribution download produced an empty archive");
        }
        String actualDigest = sha256(archivePartial);
        if (!MAVEN_SHA256.equalsIgnoreCase(actualDigest)) {
            Files.deleteIfExists(archivePartial);
            throw new IllegalStateException("Maven distribution checksum mismatch: expected="
                    + MAVEN_SHA256 + " actual=" + actualDigest);
        }
        move(archivePartial, archive);

        extractZipBounded(archive, root, MAX_MAVEN_EXTRACTED_BYTES, MAX_MAVEN_ARCHIVE_ENTRIES);
        Path mvn = mavenExecutable();
        if (!Files.isRegularFile(mvn)) {
            throw new IllegalStateException("Maven distribution archive did not contain " + mvn);
        }
        if (!CommandLocator.isWindows()) {
            try {
                Set<PosixFilePermission> permissions = EnumSet.copyOf(Files.getPosixFilePermissions(mvn));
                permissions.add(PosixFilePermission.OWNER_EXECUTE);
                Files.setPosixFilePermissions(mvn, permissions);
            } catch (UnsupportedOperationException notPosix) {
                // Non-POSIX filesystem: the archive's own permission bits are used as-is.
            }
        }
        return mvn;
    }

    private Path nodeRoot() { return toolsRoot.resolve("nodejs").resolve(NODE_VERSION); }

    Path nodeExecutable() {
        return nodeRoot().resolve(NODE_DISTRIBUTION_ID).resolve("node.exe");
    }

    private Path ensureNode() throws IOException, InterruptedException {
        Path existing = nodeExecutable();
        if (Files.isRegularFile(existing)) return existing;
        Path root = nodeRoot();
        Files.createDirectories(root);
        Path archive = root.resolve(NODE_DISTRIBUTION_ID + ".zip");
        Path archivePartial = root.resolve(NODE_DISTRIBUTION_ID + ".partial.zip");
        Files.deleteIfExists(archivePartial);

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30)).build();
        HttpRequest request = HttpRequest.newBuilder(NODE_DISTRIBUTION_URI)
                .timeout(Duration.ofMinutes(3))
                .header(USER_AGENT_HEADER, MINOS_USER_AGENT).build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            try (InputStream ignored = response.body()) { /* close error response */ }
            throw new IllegalStateException("Node.js distribution download failed with HTTP " + response.statusCode());
        }
        try (InputStream responseBody = response.body();
             BoundedInputStream bounded = new BoundedInputStream(
                     responseBody, MAX_NODE_ARCHIVE_BYTES, "Node.js distribution archive");
             OutputStream output = Files.newOutputStream(archivePartial)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = bounded.read(buffer)) >= 0) if (read > 0) output.write(buffer, 0, read);
        } catch (IOException exception) {
            Files.deleteIfExists(archivePartial);
            throw exception;
        }
        if (!Files.isRegularFile(archivePartial, LinkOption.NOFOLLOW_LINKS) || Files.size(archivePartial) == 0L) {
            Files.deleteIfExists(archivePartial);
            throw new IllegalStateException("Node.js distribution download produced an empty archive");
        }
        String actualDigest = sha256(archivePartial);
        if (!NODE_SHA256.equalsIgnoreCase(actualDigest)) {
            Files.deleteIfExists(archivePartial);
            throw new IllegalStateException("Node.js distribution checksum mismatch: expected="
                    + NODE_SHA256 + " actual=" + actualDigest);
        }
        move(archivePartial, archive);

        extractZipBounded(archive, root, MAX_NODE_EXTRACTED_BYTES, MAX_NODE_ARCHIVE_ENTRIES);
        Path node = nodeExecutable();
        if (!Files.isRegularFile(node)) {
            throw new IllegalStateException("Node.js distribution archive did not contain " + node);
        }
        return node;
    }

    /**
     * Extracts a zip archive under {@code destinationRoot}, rejecting any entry that would resolve
     * outside it (zip-slip) and bounding both entry count and total extracted bytes so a corrupted
     * or oversized archive cannot turn installation into unbounded disk consumption.
     */
    private static void extractZipBounded(
            Path archive, Path destinationRoot, long maxTotalBytes, long maxEntries
    ) throws IOException {
        Path root = destinationRoot.toAbsolutePath().normalize();
        Files.createDirectories(root);
        long totalBytes = 0L;
        long entries = 0L;
        try (InputStream input = Files.newInputStream(archive); ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > maxEntries) throw new IOException("archive has too many entries: " + archive);
                Path target = root.resolve(entry.getName()).normalize();
                if (!target.startsWith(root)) {
                    throw new IOException("archive entry escapes destination: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                try (OutputStream output = Files.newOutputStream(
                        target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = zip.read(buffer)) >= 0) {
                        if (read == 0) continue;
                        totalBytes += read;
                        if (totalBytes > maxTotalBytes) {
                            throw new IOException("archive exceeds extraction size budget: " + archive);
                        }
                        output.write(buffer, 0, read);
                    }
                }
            }
        }
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private Optional<Path> coursierExecutable() {
        Path managed = toolsRoot.resolve("coursier").resolve(COURSIER_LAUNCHER_ID)
                .resolve(CommandLocator.isWindows() ? "cs.exe" : "cs");
        return Files.isRegularFile(managed) ? Optional.of(managed) : CommandLocator.find("cs");
    }

    private Path typeScriptRoot() { return toolsRoot.resolve("scip-typescript").resolve(SCIP_TYPESCRIPT_VERSION); }

    private Path typeScriptExecutable() {
        return typeScriptRoot().resolve(NODE_MODULES_DIR).resolve(".bin")
                .resolve(CommandLocator.isWindows() ? "scip-typescript.cmd" : "scip-typescript");
    }

    Path typeScriptMainScript() {
        return typeScriptRoot().resolve(NODE_MODULES_DIR).resolve("@sourcegraph")
                .resolve("scip-typescript").resolve("dist").resolve("src").resolve("main.js");
    }

    private Path scipJavaRuntimeRoot() { return toolsRoot.resolve("scip-java").resolve(SCIP_JAVA_VERSION).resolve("runtime"); }
    private Path scipJavaWindowsRunner() { return scipJavaRuntimeRoot().resolve(WINDOWS_RUNNER_RESOURCE); }

    private boolean windowsRuntimeInstalled() {
        Path runtime = scipJavaRuntimeRoot();
        return Files.isRegularFile(runtime.resolve(WINDOWS_RUNNER_RESOURCE))
                && Files.isRegularFile(runtime.resolve(WINDOWS_PATCH_RESOURCE));
    }

    static Optional<Path> powerShellExecutable() { return CommandLocator.find("powershell").or(() -> CommandLocator.find("pwsh")); }

    private static boolean gitBashAvailable() {
        return CommandLocator.find("git").flatMap(ManagedScipProviderRuntimeManager::gitBashForGit).isPresent();
    }

    static Optional<Path> gitBashForGit(Path gitExecutable) {
        Path gitRoot = gitExecutable.getParent() == null ? null : gitExecutable.getParent().getParent();
        if (gitRoot == null) return Optional.empty();
        Path gitBash = gitRoot.resolve("bin").resolve("bash.exe").toAbsolutePath().normalize();
        return Files.isRegularFile(gitBash) ? Optional.of(gitBash) : Optional.empty();
    }

    private static boolean cSharpCompilerAvailable() {
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot != null && !systemRoot.isBlank()) {
            Path root = Path.of(systemRoot);
            if (Files.isRegularFile(root.resolve("Microsoft.NET").resolve("Framework64").resolve("v4.0.30319").resolve("csc.exe"))) return true;
            if (Files.isRegularFile(root.resolve("Microsoft.NET").resolve("Framework").resolve("v4.0.30319").resolve("csc.exe"))) return true;
        }
        return CommandLocator.find("csc").isPresent();
    }

    private static void run(List<String> command, Path workingDirectory, Path log, Duration timeout)
            throws IOException, InterruptedException {
        Files.createDirectories(log.toAbsolutePath().normalize().getParent());
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workingDirectory.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        BoundedProcessOutput.Capture capture = BoundedProcessOutput.capture(process, log, null);
        boolean completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!completed) {
            process.descendants().toList().reversed().forEach(handle -> { if (handle.isAlive()) handle.destroyForcibly(); });
            if (process.isAlive()) process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            capture.await();
            throw new IllegalStateException("tool command timed out; see " + log);
        }
        capture.await();
        if (process.exitValue() != 0) throw new IllegalStateException("tool command failed with code " + process.exitValue() + "; see " + log);
    }

    private static void deleteRecursively(Path path) throws IOException {
        FileTreeOperations.deleteRecursively(path);
    }

    private static void move(Path source, Path target) throws IOException {
        Files.createDirectories(target.toAbsolutePath().normalize().getParent());
        try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException exception) { Files.move(source, target, StandardCopyOption.REPLACE_EXISTING); }
    }
}
