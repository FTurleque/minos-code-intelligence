package com.minos.adapter.scip.runtime;

import com.minos.io.PrivateLocalStorage;
import com.minos.io.ConfinedFileOpener;
import com.minos.io.BoundedFileLease;
import com.minos.io.BoundedInputStream;
import com.minos.io.BoundedLineReader;
import com.minos.io.FileTreeOperations;
import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.runtime.local.BoundedProcessOutput;
import com.minos.runtime.local.CommandLocator;
import com.minos.runtime.ProviderRuntimeManager;
import com.minos.runtime.ProviderRuntimeStatus;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Local manager for MINOS-qualified SCIP runtimes. */
public final class ManagedScipProviderRuntimeManager implements ProviderRuntimeManager {

    private static final int MAX_INSTALL_LOG_LINE_CHARS = 64 * 1024;
    private static final String NODE_MODULES_DIR = "node_modules";
    private static final String MINOS_USER_AGENT = "MINOS-Code-Intelligence";

    public static final String SCIP_TYPESCRIPT_ID = "scip-typescript";
    public static final String SCIP_TYPESCRIPT_VERSION = "0.4.0";
    public static final String SCIP_JAVA_ID = "scip-java";
    public static final String SCIP_JAVA_VERSION = "0.13.1";
    public static final String SCIP_JAVA_COORDINATE = "org.scip-code:scip-java:" + SCIP_JAVA_VERSION;
    static final String SCIP_JAVA_MAIN_CLASS = "org.scip_code.scip_java.ScipJava";

    // The URL, version and SHA-256 of every pinned artifact below live in embedded-tools.json (the one
    // description shared with the Docker release image and the Windows distribution build); nothing here
    // repeats them.
    private static final String COURSIER_ID = "coursier";

    // A project's own Maven wrapper or a host-installed `mvn` cannot be reached by the Windows
    // AppContainer sandbox in general: wrapper discovery walks ancestor directories the sandbox
    // never grants, and a host `mvn` may sit anywhere PATH names, most of which carry no MINOS
    // grant either. scip-java on Windows therefore gets its own MINOS-managed Maven, mirroring how
    // Coursier and scip-typescript are already fetched, checksummed and confined to MINOS_HOME/tools
    // -- a location the sandbox already grants through the existing managed-tools root.
    private static final String MAVEN_ID = "maven";
    private static final String NODE_ID = "nodejs";
    private static final String TYPESCRIPT_MODULES_ID = "scip-typescript-modules";
    private static final String JAVA_CLASSPATH_ID = "scip-java-classpath";
    private static final String EMBEDDED_TREE_SOURCE = "embedded-tools-manifest-sha256";
    private static final String CLASSPATH_DIRECTORY = "classpath";
    private static final String CLASSPATH_FILE = "classpath.txt";
    private static final long MAX_COURSIER_ARCHIVE_BYTES = 64L * 1024L * 1024L;
    private static final long MAX_MAVEN_ARCHIVE_BYTES = 16L * 1024L * 1024L;
    private static final long MAX_MAVEN_ARCHIVE_ENTRIES = 4_096L;
    private static final long MAX_MAVEN_EXTRACTED_BYTES = 64L * 1024L * 1024L;
    private static final long MAX_NODE_ARCHIVE_BYTES = 128L * 1024L * 1024L;
    private static final long MAX_NODE_ARCHIVE_ENTRIES = 8_192L;
    private static final long MAX_NODE_EXTRACTED_BYTES = 256L * 1024L * 1024L;
    private static final long MAX_ASSEMBLED_ARCHIVE_BYTES = 256L * 1024L * 1024L;
    private static final long MAX_ASSEMBLED_ENTRIES = 50_000L;
    private static final long MAX_ASSEMBLED_EXTRACTED_BYTES = 512L * 1024L * 1024L;
    private static final ReentrantLock SEED_LOCK = new ReentrantLock();
    private static final Duration SEED_LOCK_TIMEOUT = Duration.ofMinutes(5);

    private static final String SCIP_TYPESCRIPT_NPM_LOCK_RESOURCE = "scip-typescript-package-lock.json";
    private static final String SCIP_TYPESCRIPT_NPM_INTEGRITY = "sha512-k+AtsrqmS41Sd5qjkZlHcmvoSQIvBOonRj4jpgp0KNFM6aqvMGpdSuPUqrUcg8ENTKjUbfaUVszgQwq3bCOvwA==";

    // A host `node` resolved from PATH can sit anywhere -- typically under Program Files or a
    // per-user npm install -- none of which the non-elevated Windows AppContainer sandbox can grant
    // access to. scip-typescript.cmd's own npm-generated shim resolves `node` via PATH internally,
    // which the sandbox's static command-line ACL computation can never see. scip-typescript on
    // Windows therefore gets its own MINOS-managed, portable Node.js, invoked directly against the
    // package's entry script (bypassing the cmd.exe shim, and cmd.exe entirely) so both the
    // interpreter and the script are ordinary, sandbox-grantable paths under MINOS_HOME/tools.
    private static final String WINDOWS_RUNNER_RESOURCE = "scip-java-windows-runner.ps1";
    private static final String WINDOWS_PATCH_RESOURCE = "ScipWriter.java";

    private static final EmbeddedToolsCatalog CATALOG = EmbeddedToolsCatalog.load();

    private final Path home;
    private final Path toolsRoot;
    private final ToolOriginLedger origins;
    private volatile PinnedArtifactSource source;

    public ManagedScipProviderRuntimeManager(Path minosHome) {
        this.home = Objects.requireNonNull(minosHome, "minosHome").toAbsolutePath().normalize();
        this.toolsRoot = home.resolve("tools");
        this.origins = new ToolOriginLedger(toolsRoot);
    }

    /** The distribution payload of this installation, looked up once and only when a tool is needed. */
    private PinnedArtifactSource source() {
        PinnedArtifactSource current = source;
        if (current == null) {
            current = PinnedArtifactSource.forHost();
            source = current;
        }
        return current;
    }

    private static EmbeddedToolsCatalog.Artifact coursierArtifact() {
        return CATALOG.artifact(COURSIER_ID, EmbeddedToolsCatalog.PLATFORM_WINDOWS_X64);
    }

    private static EmbeddedToolsCatalog.Artifact mavenArtifact() {
        return CATALOG.artifact(MAVEN_ID, EmbeddedToolsCatalog.PLATFORM_ANY);
    }

    private static EmbeddedToolsCatalog.Artifact nodeArtifact() {
        return CATALOG.artifact(NODE_ID, EmbeddedToolsCatalog.PLATFORM_WINDOWS_X64);
    }

    private static String coursierLauncherId() {
        return "windows-x64-" + coursierArtifact().version().substring(0, 12);
    }

    private static String nodeDistributionId() {
        return "node-v" + nodeArtifact().version() + "-win-x64";
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
                            CommandLocator.isWindows() ? mavenExecutable() : null,
                            Files.isRegularFile(scipJavaClasspathFile(), LinkOption.NOFOLLOW_LINKS)
                                    ? scipJavaClasspathFile() : null));
            default -> throw new IllegalArgumentException("unknown managed provider: " + providerId);
        };
    }

    private ProviderRuntimeStatus inspectTypeScript() {
        SeedOutcome seeded = seedFromPayload(SCIP_TYPESCRIPT_ID);
        return seeded.applyTo(inspectTypeScriptInstalled(), originDiagnostic(SCIP_TYPESCRIPT_ID));
    }

    private ProviderRuntimeStatus inspectTypeScriptInstalled() {
        List<String> diagnostics = new ArrayList<>();
        boolean packageInstalled = Files.isRegularFile(typeScriptMainScript());
        if (!packageInstalled) diagnostics.add("managed scip-typescript " + SCIP_TYPESCRIPT_VERSION + " is not installed");

        Path executable;
        if (CommandLocator.isWindows()) {
            executable = nodeExecutable();
            if (!Files.isRegularFile(executable)) {
                diagnostics.add("managed Node.js " + nodeArtifact().version() + " runtime is not installed in MINOS_HOME/tools");
            }
        } else {
            Optional<Path> node = CommandLocator.find("node");
            Optional<Path> npm = CommandLocator.find("npm");
            if (node.isEmpty()) diagnostics.add(ExternalPrerequisite.of("Node.js is not available in PATH"));
            if (npm.isEmpty()) diagnostics.add(ExternalPrerequisite.of("npm is not available in PATH"));
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
        SeedOutcome seeded = seedFromPayload(SCIP_JAVA_ID);
        return seeded.applyTo(inspectJavaInstalled(), originDiagnostic(SCIP_JAVA_ID));
    }

    private ProviderRuntimeStatus inspectJavaInstalled() {
        List<String> diagnostics = new ArrayList<>();
        Optional<Path> coursier = coursierExecutable();
        boolean windowsRuntimeInstalled = !CommandLocator.isWindows() || windowsRuntimeInstalled();
        boolean mavenInstalled = !CommandLocator.isWindows() || Files.isRegularFile(mavenExecutable());
        if (coursier.isEmpty()) diagnostics.add("Coursier is not installed in MINOS_HOME/tools and was not found in PATH");
        if (!windowsRuntimeInstalled) diagnostics.add("managed scip-java " + SCIP_JAVA_VERSION + " Windows compatibility runtime is not installed");
        if (!mavenInstalled) diagnostics.add("managed Maven " + mavenArtifact().version() + " is not installed in MINOS_HOME/tools");
        if (CommandLocator.isWindows()) {
            if (powerShellExecutable().isEmpty()) diagnostics.add(ExternalPrerequisite.of("PowerShell (powershell.exe or pwsh.exe) is required for scip-java on Windows"));
            if (!gitBashAvailable()) diagnostics.add(ExternalPrerequisite.of("Git Bash (bash.exe) is required for scip-java on Windows"));
            if (!cSharpCompilerAvailable()) diagnostics.add(ExternalPrerequisite.of("csc.exe is required to build scip-java Windows command shims"));
        }
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome == null || javaHome.isBlank()) {
            diagnostics.add(ExternalPrerequisite.of("JAVA_HOME is not set to the project JDK"));
        } else {
            Path javac = Path.of(javaHome).resolve("bin").resolve(CommandLocator.isWindows() ? "javac.exe" : "javac");
            if (!Files.isRegularFile(javac)) diagnostics.add(ExternalPrerequisite.of("JAVA_HOME does not contain javac: " + javaHome));
        }
        boolean installed = coursier.isPresent() && windowsRuntimeInstalled && mavenInstalled;
        ProviderRuntimeStatus.State state = !installed
                ? ProviderRuntimeStatus.State.NOT_INSTALLED
                : diagnostics.isEmpty() ? ProviderRuntimeStatus.State.READY : ProviderRuntimeStatus.State.BLOCKED;
        return new ProviderRuntimeStatus(SCIP_JAVA_ID, SCIP_JAVA_VERSION, state, coursier, diagnostics);
    }

    private ProviderRuntimeStatus installTypeScript() throws Exception {
        PrivateLocalStorage.ensurePrivateDirectory(toolsRoot);
        seedFromPayload(SCIP_TYPESCRIPT_ID).requireAccepted();
        ProviderRuntimeStatus seededStatus = inspectTypeScript();
        if (seededStatus.ready()) return seededStatus;
        Path npm = CommandLocator.find("npm")
                .orElseThrow(() -> new IllegalStateException("npm is required to install scip-typescript"));
        CommandLocator.find("node").orElseThrow(() -> new IllegalStateException("Node.js is required to run scip-typescript"));
        PrivateLocalStorage.ensurePrivateDirectory(toolsRoot);
        if (CommandLocator.isWindows()) {
            ensureNode(true);
        }
        Path destination = typeScriptRoot();
        Path partial = destination.resolveSibling(destination.getFileName() + ".partial");
        deleteRecursively(partial);
        PrivateLocalStorage.ensurePrivateDirectory(partial);
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
        PrivateLocalStorage.ensurePrivateDirectory(toolsRoot);
        seedFromPayload(SCIP_JAVA_ID).requireAccepted();
        Path coursier = ensureCoursier(true);
        if (CommandLocator.isWindows()) {
            installJavaWindowsRuntime();
            ensureMaven(true);
        }
        if (!Files.isRegularFile(scipJavaClasspathFile(), LinkOption.NOFOLLOW_LINKS)) {
            run(List.of(coursier.toString(), "--help"), home, toolsRoot.resolve("coursier-verify.log"), Duration.ofMinutes(1));
            Path scipJavaLog = toolsRoot.resolve("scip-java-install.log");
            run(scipJavaInstallationProbe(coursier), home, scipJavaLog, Duration.ofMinutes(10));
            requireExpectedScipJavaVersion(scipJavaLog);
        }
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
                     ConfinedFileOpener.openRegularFileNoFollow(log), BoundedProcessOutput.DEFAULT_MAX_BYTES_PER_STREAM,
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
        PrivateLocalStorage.ensurePrivateDirectory(runtime);
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

    private Path ensureCoursier(boolean allowNetwork) throws Exception {
        Optional<Path> existing = coursierExecutable();
        if (existing.isPresent()) return existing.orElseThrow();
        if (!CommandLocator.isWindows()) {
            throw new IllegalStateException("automatic Coursier installation is currently packaged for Windows x64; install `cs` in PATH");
        }
        Path directory = toolsRoot.resolve("coursier").resolve(coursierLauncherId());
        PrivateLocalStorage.ensurePrivateDirectory(directory);
        Path destination = directory.resolve("cs.exe");
        Path archive = directory.resolve("cs-x86_64-pc-win32.zip");
        Path archivePartial = directory.resolve("cs-x86_64-pc-win32.partial.zip");
        Path executablePartial = directory.resolve("cs.partial.exe");
        Files.deleteIfExists(archivePartial);
        Files.deleteIfExists(executablePartial);

        PinnedArtifactSource.Origin origin = source().acquire(
                coursierArtifact(), archivePartial, MAX_COURSIER_ARCHIVE_BYTES, MINOS_USER_AGENT, allowNetwork);
        move(archivePartial, archive);

        int executableEntries = 0;
        try (InputStream input = ConfinedFileOpener.openRegularFileNoFollow(archive); ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory() && entry.getName().toLowerCase(Locale.ROOT).endsWith(".exe")) {
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
        origins.record(COURSIER_ID, origin);
        return destination;
    }

    private Path mavenRoot() { return toolsRoot.resolve("maven").resolve(mavenArtifact().version()); }

    Path mavenExecutable() {
        return mavenRoot().resolve("apache-maven-" + mavenArtifact().version()).resolve("bin")
                .resolve(CommandLocator.isWindows() ? "mvn.cmd" : "mvn");
    }

    private Path ensureMaven(boolean allowNetwork) throws Exception {
        Path existing = mavenExecutable();
        if (Files.isRegularFile(existing)) return existing;
        Path root = mavenRoot();
        PrivateLocalStorage.ensurePrivateDirectory(root);
        String archiveName = "apache-maven-" + mavenArtifact().version() + "-bin";
        Path archive = root.resolve(archiveName + ".zip");
        Path archivePartial = root.resolve(archiveName + ".partial.zip");
        Files.deleteIfExists(archivePartial);

        PinnedArtifactSource.Origin origin = source().acquire(
                mavenArtifact(), archivePartial, MAX_MAVEN_ARCHIVE_BYTES, MINOS_USER_AGENT, allowNetwork);
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
        origins.record(MAVEN_ID, origin);
        return mvn;
    }

    private Path nodeRoot() { return toolsRoot.resolve("nodejs").resolve(nodeArtifact().version()); }

    Path nodeExecutable() {
        return nodeRoot().resolve(nodeDistributionId()).resolve("node.exe");
    }

    private Path ensureNode(boolean allowNetwork) throws IOException, InterruptedException {
        Path existing = nodeExecutable();
        if (Files.isRegularFile(existing)) return existing;
        Path root = nodeRoot();
        PrivateLocalStorage.ensurePrivateDirectory(root);
        Path archive = root.resolve(nodeDistributionId() + ".zip");
        Path archivePartial = root.resolve(nodeDistributionId() + ".partial.zip");
        Files.deleteIfExists(archivePartial);

        PinnedArtifactSource.Origin origin = source().acquire(
                nodeArtifact(), archivePartial, MAX_NODE_ARCHIVE_BYTES, MINOS_USER_AGENT, allowNetwork);
        move(archivePartial, archive);

        extractZipBounded(archive, root, MAX_NODE_EXTRACTED_BYTES, MAX_NODE_ARCHIVE_ENTRIES);
        Path node = nodeExecutable();
        if (!Files.isRegularFile(node)) {
            throw new IllegalStateException("Node.js distribution archive did not contain " + node);
        }
        origins.record(NODE_ID, origin);
        return node;
    }

    /** What the distribution payload did for a provider on this inspection. */
    private record SeedOutcome(boolean refused, List<String> diagnostics) {
        static final SeedOutcome NONE = new SeedOutcome(false, List.of());

        /** Adds the seeding diagnostics, and turns an altered payload into a refusal, never a silent fallback. */
        ProviderRuntimeStatus applyTo(ProviderRuntimeStatus status, Optional<String> origin) {
            List<String> merged = new ArrayList<>(status.diagnostics());
            merged.addAll(diagnostics);
            origin.ifPresent(merged::add);
            ProviderRuntimeStatus.State state = refused ? ProviderRuntimeStatus.State.INVALID : status.state();
            if (merged.equals(status.diagnostics()) && state == status.state()) return status;
            return new ProviderRuntimeStatus(
                    status.providerId(), status.version(), state, status.executable(), merged, status.requiredByDefault());
        }

        void requireAccepted() {
            if (refused) throw new PinnedArtifactSource.EmbeddedToolIntegrityException(String.join("; ", diagnostics));
        }
    }

    /**
     * Seeds {@code MINOS_HOME/tools} from the tools shipped with the installation, without any network access,
     * for a provider whose components are missing there. Idempotent and safe under concurrency (a bounded
     * lease serialises the processes); an altered payload is refused, never replaced by a download.
     */
    private SeedOutcome seedFromPayload(String providerId) {
        if (!EmbeddedToolsCatalog.PLATFORM_WINDOWS_X64.equals(EmbeddedToolsCatalog.currentPlatform())) {
            return SeedOutcome.NONE;
        }
        try {
            SeedOutcome tampered = verifyInstalledEmbeddedTrees(providerId);
            if (tampered != null) return tampered;
            if (!needsSeeding(providerId)) return SeedOutcome.NONE;
            PrivateLocalStorage.ensurePrivateDirectory(toolsRoot);
            try (BoundedFileLease ignored = BoundedFileLease.acquire(
                    toolsRoot.resolve(".embedded-seed.lock"), SEED_LOCK, SEED_LOCK_TIMEOUT, "embedded tools seeding")) {
                if (needsSeeding(providerId)) seedComponents(providerId);
            }
            return SeedOutcome.NONE;
        } catch (PinnedArtifactSource.EmbeddedToolIntegrityException refused) {
            return new SeedOutcome(true, List.of("embedded tools were refused: " + refused.getMessage()));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new SeedOutcome(false, List.of("embedded tools seeding was interrupted"));
        } catch (Exception failure) {
            return new SeedOutcome(false, List.of("embedded tools could not be seeded: " + failure.getClass().getSimpleName()));
        }
    }

    private boolean needsSeeding(String providerId) throws IOException {
        for (String component : CATALOG.provider(providerId).components()) {
            if (shippedAndMissing(component)) return true;
        }
        return false;
    }

    private boolean shippedAndMissing(String component) throws IOException {
        String platform = EmbeddedToolsCatalog.PLATFORM_WINDOWS_X64;
        Optional<EmbeddedToolsCatalog.Artifact> artifact = CATALOG.findArtifact(component, platform);
        if (artifact.isPresent()) {
            return source().ships(artifact.orElseThrow()) && !installed(component);
        }
        Optional<EmbeddedToolsCatalog.Assembled> assembled = CATALOG.findAssembled(component, platform);
        return assembled.isPresent() && source().shipsAssembled(assembled.orElseThrow()) && !installed(component);
    }

    private boolean installed(String component) {
        return switch (component) {
            case COURSIER_ID -> coursierExecutable().isPresent();
            case MAVEN_ID -> Files.isRegularFile(mavenExecutable());
            case NODE_ID -> Files.isRegularFile(nodeExecutable());
            case TYPESCRIPT_MODULES_ID -> Files.isRegularFile(typeScriptMainScript());
            case JAVA_CLASSPATH_ID -> Files.isRegularFile(scipJavaClasspathFile(), LinkOption.NOFOLLOW_LINKS);
            default -> true;
        };
    }

    private void seedComponents(String providerId) throws Exception {
        for (String component : CATALOG.provider(providerId).components()) {
            if (!shippedAndMissing(component)) continue;
            switch (component) {
                case COURSIER_ID -> ensureCoursier(false);
                case MAVEN_ID -> ensureMaven(false);
                case NODE_ID -> ensureNode(false);
                case TYPESCRIPT_MODULES_ID -> seedAssembledTree(
                        CATALOG.findAssembled(component, EmbeddedToolsCatalog.PLATFORM_WINDOWS_X64).orElseThrow(),
                        typeScriptRoot(), SCIP_TYPESCRIPT_VERSION);
                case JAVA_CLASSPATH_ID -> {
                    installJavaWindowsRuntime();
                    seedAssembledTree(
                            CATALOG.findAssembled(component, EmbeddedToolsCatalog.PLATFORM_WINDOWS_X64).orElseThrow(),
                            scipJavaClasspathRoot(), SCIP_JAVA_VERSION);
                }
                default -> { }
            }
        }
    }

    /**
     * Extracts an assembled component into a private partial directory, stamps the integrity markers the
     * other managed trees carry, and swaps it in. The archive is verified against the manifest of the
     * distribution (no upstream pins its bytes) before a single entry is extracted.
     */
    private void seedAssembledTree(EmbeddedToolsCatalog.Assembled component, Path destination, String version)
            throws Exception {
        Path archivePartial = toolsRoot.resolve(".seed-" + component.id() + ".partial.zip");
        Path partial = destination.resolveSibling(destination.getFileName() + ".partial");
        Files.deleteIfExists(archivePartial);
        deleteRecursively(partial);
        try {
            source().copyAssembled(component, archivePartial, MAX_ASSEMBLED_ARCHIVE_BYTES);
            extractZipBounded(archivePartial, partial, MAX_ASSEMBLED_EXTRACTED_BYTES, MAX_ASSEMBLED_ENTRIES);
            ManagedPolyglotScipRuntimeManager.writeManagedMarkers(partial, version, EMBEDDED_TREE_SOURCE);
            deleteRecursively(destination);
            move(partial, destination);
            origins.record(component.id(), PinnedArtifactSource.Origin.EMBEDDED);
        } finally {
            Files.deleteIfExists(archivePartial);
            deleteRecursively(partial);
        }
    }

    /** An embedded tree that no longer matches the digest stamped when it was seeded is refused. */
    private SeedOutcome verifyInstalledEmbeddedTrees(String providerId) {
        for (String component : CATALOG.provider(providerId).components()) {
            Path tree = switch (component) {
                case TYPESCRIPT_MODULES_ID -> typeScriptRoot();
                case JAVA_CLASSPATH_ID -> scipJavaClasspathRoot();
                default -> null;
            };
            if (tree == null || !Files.isDirectory(tree, LinkOption.NOFOLLOW_LINKS)) continue;
            if (!ManagedPolyglotScipRuntimeManager.installSourceMatches(tree, EMBEDDED_TREE_SOURCE)) continue;
            if (!ManagedPolyglotScipRuntimeManager.integrityManifestMatches(tree)) {
                return new SeedOutcome(true, List.of(
                        "embedded tools were refused: " + component + " no longer matches its integrity manifest"));
            }
        }
        return null;
    }

    private Optional<String> originDiagnostic(String providerId) {
        List<String> parts = new ArrayList<>();
        for (String component : CATALOG.provider(providerId).components()) {
            origins.origin(component).ifPresent(origin -> parts.add(component + "=" + origin));
        }
        return parts.isEmpty() ? Optional.empty() : Optional.of("tools origin: " + String.join(", ", parts));
    }

    /**
     * Extracts a zip archive under {@code destinationRoot}, rejecting any entry that would resolve
     * outside it (zip-slip) and bounding both entry count and total extracted bytes so a corrupted
     * or oversized archive cannot turn installation into unbounded disk consumption.
     */
    static void extractZipBounded(
            Path archive, Path destinationRoot, long maxTotalBytes, long maxEntries
    ) throws IOException {
        Path root = destinationRoot.toAbsolutePath().normalize();
        PrivateLocalStorage.ensurePrivateDirectory(root);
        long totalBytes = 0L;
        long entries = 0L;
        try (InputStream input = ConfinedFileOpener.openRegularFileNoFollow(archive); ZipInputStream zip = new ZipInputStream(input)) {
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

    private Optional<Path> coursierExecutable() {
        Path managed = toolsRoot.resolve("coursier").resolve(coursierLauncherId())
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
    private Path scipJavaRoot() { return toolsRoot.resolve("scip-java").resolve(SCIP_JAVA_VERSION); }
    private Path scipJavaClasspathRoot() { return scipJavaRoot().resolve(CLASSPATH_DIRECTORY); }
    Path scipJavaClasspathFile() { return scipJavaClasspathRoot().resolve(CLASSPATH_FILE); }

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
        PrivateLocalStorage.ensurePrivateDirectory(log.toAbsolutePath().normalize().getParent());
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
        PrivateLocalStorage.ensurePrivateDirectory(target.toAbsolutePath().normalize().getParent());
        try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException exception) { Files.move(source, target, StandardCopyOption.REPLACE_EXISTING); }
    }
}
