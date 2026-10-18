package com.minos.adapter.scip.runtime.install;

import com.minos.adapter.scip.runtime.plan.ScipJavaClasspath;
import com.minos.adapter.scip.runtime.plan.ScipJavaProcessPlanFactory;
import com.minos.adapter.scip.runtime.plan.ScipTypeScriptProcessPlanFactory;
import com.minos.io.PrivateLocalStorage;
import com.minos.io.ConfinedFileOpener;
import com.minos.io.DurableAtomicFile;
import com.minos.io.BoundedFileLease;
import com.minos.io.BoundedInputStream;
import com.minos.io.BoundedLineReader;
import com.minos.io.FileTreeOperations;
import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.runtime.local.process.InstallationProcessEnvironment;
import com.minos.runtime.local.process.BoundedProcessOutput;
import com.minos.runtime.local.process.CommandLocator;
import com.minos.runtime.ProviderRuntimeManager;
import com.minos.runtime.ProviderRuntimeStatus;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
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
    // The largest jar of the lock is 54 MiB; a copy stops well before filling the disk.
    private static final long MAX_CLASSPATH_JAR_BYTES = 128L * 1024L * 1024L;
    private static final long MAX_ASSEMBLED_ARCHIVE_BYTES = 256L * 1024L * 1024L;
    private static final long MAX_ASSEMBLED_ENTRIES = 50_000L;
    private static final long MAX_ASSEMBLED_EXTRACTED_BYTES = 512L * 1024L * 1024L;
    private static final ReentrantLock SEED_LOCK = new ReentrantLock();
    private static final Duration SEED_LOCK_TIMEOUT = Duration.ofMinutes(5);

    private static final String SCIP_TYPESCRIPT_NPM_LOCK_RESOURCE = EmbeddedToolsCatalog.RESOURCE_ROOT + "scip-typescript-package-lock.json";
    private static final String SCIP_TYPESCRIPT_NPM_INTEGRITY = "sha512-k+AtsrqmS41Sd5qjkZlHcmvoSQIvBOonRj4jpgp0KNFM6aqvMGpdSuPUqrUcg8ENTKjUbfaUVszgQwq3bCOvwA==";

    // A host `node` resolved from PATH can sit anywhere -- typically under Program Files or a
    // per-user npm install -- none of which the non-elevated Windows AppContainer sandbox can grant
    // access to. scip-typescript.cmd's own npm-generated shim resolves `node` via PATH internally,
    // which the sandbox's static command-line ACL computation can never see. scip-typescript on
    // Windows therefore gets its own MINOS-managed, portable Node.js, invoked directly against the
    // package's entry script (bypassing the cmd.exe shim, and cmd.exe entirely) so both the
    // interpreter and the script are ordinary, sandbox-grantable paths under MINOS_HOME/tools.
    /**
     * What a Windows installation reports until the scip-java chain is run end to end under the AppContainer sandbox.
     * The chain has only been run outside it: capability honesty forbids calling it ready.
     */
    static final String SANDBOXED_EXECUTION_NOT_QUALIFIED = "scip-java execution under the Windows AppContainer sandbox "
            + "is not qualified; index Java projects with the Docker runtime (docs/user/docker-runtime.md)";

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
                            scipJavaClasspathFile(), jars -> ClasspathLock.packaged().verify(jars)));
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
        boolean classpathInstalled = scipJavaClasspathInstalled();
        addMissingJavaToolDiagnostics(
                diagnostics, coursier.isPresent(), windowsRuntimeInstalled, mavenInstalled, classpathInstalled);
        if (CommandLocator.isWindows()) {
            addJavaWindowsPrerequisiteDiagnostics(diagnostics);
        }
        addProjectJdkDiagnostics(diagnostics);
        boolean installed = coursier.isPresent() && windowsRuntimeInstalled && mavenInstalled && classpathInstalled;
        ProviderRuntimeStatus.State state = !installed
                ? ProviderRuntimeStatus.State.NOT_INSTALLED
                : diagnostics.isEmpty() ? ProviderRuntimeStatus.State.READY : ProviderRuntimeStatus.State.BLOCKED;
        return new ProviderRuntimeStatus(SCIP_JAVA_ID, SCIP_JAVA_VERSION, state, coursier, diagnostics);
    }

    /** The MINOS-owned parts of scip-java that are not installed, each named with where it is expected. */
    private void addMissingJavaToolDiagnostics(
            List<String> diagnostics,
            boolean coursierInstalled,
            boolean windowsRuntimeInstalled,
            boolean mavenInstalled,
            boolean classpathInstalled
    ) {
        if (!coursierInstalled) {
            diagnostics.add("Coursier is not installed in MINOS_HOME/tools and was not found in PATH");
        }
        if (!windowsRuntimeInstalled) {
            diagnostics.add("managed scip-java " + SCIP_JAVA_VERSION
                    + " Windows compatibility runtime is not installed");
        }
        if (!mavenInstalled) {
            diagnostics.add("managed Maven " + mavenArtifact().version() + " is not installed in MINOS_HOME/tools");
        }
        if (!classpathInstalled) {
            diagnostics.add("managed scip-java " + SCIP_JAVA_VERSION
                    + " classpath is not installed in MINOS_HOME/tools");
        }
    }

    /** Machine prerequisites of scip-java on Windows, plus the declared absence of a qualified sandboxed run. */
    private void addJavaWindowsPrerequisiteDiagnostics(List<String> diagnostics) {
        if (powerShellExecutable().isEmpty()) {
            diagnostics.add(ExternalPrerequisite.of("Windows PowerShell 5.1 "
                    + "(System32\\WindowsPowerShell\\v1.0\\powershell.exe) is required for scip-java on Windows"));
        }
        if (!gitBashAvailable()) {
            diagnostics.add(ExternalPrerequisite.of("Git Bash (bash.exe) is required for scip-java on Windows"));
        }
        if (!cSharpCompilerAvailable()) {
            diagnostics.add(ExternalPrerequisite.of("csc.exe is required to build scip-java Windows command shims"));
        }
        diagnostics.add(SANDBOXED_EXECUTION_NOT_QUALIFIED);
    }

    private static void addProjectJdkDiagnostics(List<String> diagnostics) {
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome == null || javaHome.isBlank()) {
            diagnostics.add(ExternalPrerequisite.of("JAVA_HOME is not set to the project JDK"));
            return;
        }
        Path javac = Path.of(javaHome).resolve("bin").resolve(CommandLocator.isWindows() ? "javac.exe" : "javac");
        if (!Files.isRegularFile(javac)) {
            diagnostics.add(ExternalPrerequisite.of("JAVA_HOME does not contain javac: " + javaHome));
        }
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
            moveDirectory(partial, destination);
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
        ClasspathLock lock = ClasspathLock.packaged();
        if (!installedClasspathVerifies(lock)) {
            run(List.of(coursier.toString(), "--help"), home, toolsRoot.resolve("coursier-verify.log"), Duration.ofMinutes(1));
            List<Path> jars = fetchVerifiedClasspath(
                    coursier, home, toolsRoot.resolve("scip-java-install.log"), lock);
            installClasspath(jars, lock);
        }
        ProviderRuntimeStatus status = inspectJava();
        if (!status.ready()) throw new IllegalStateException("scip-java installation is incomplete: " + String.join("; ", status.diagnostics()));
        return status;
    }

    /** Resolution only: scip-java is never launched, so nothing from the network runs before it is verified. */
    static List<String> scipJavaFetchCommand(Path coursier) {
        return List.of(coursier.toString(), "fetch", "--classpath", SCIP_JAVA_COORDINATE);
    }

    /**
     * Resolves the scip-java classpath with {@code coursier fetch} and compares every jar with the pinned lock before
     * anything is kept. A mismatch (a jar replaced upstream or by a mirror, added, or missing) fails the installation.
     */
    static List<Path> fetchVerifiedClasspath(Path coursier, Path workingDirectory, Path log, ClasspathLock lock)
            throws IOException, InterruptedException {
        run(scipJavaFetchCommand(coursier), workingDirectory, log, Duration.ofMinutes(10));
        List<Path> jars = resolvedJars(log);
        lock.verify(jars);
        return jars;
    }

    /** The classpath is the last line of the fetch output that names only absolute jar files. */
    static List<Path> resolvedJars(Path log) throws IOException {
        if (!Files.isRegularFile(log, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(log)) {
            throw new IOException("scip-java installation log must be a regular non-symbolic file");
        }
        List<Path> classpath = List.of();
        try (BoundedInputStream input = new BoundedInputStream(
                     ConfinedFileOpener.openRegularFileNoFollow(log), BoundedProcessOutput.DEFAULT_MAX_BYTES_PER_STREAM,
                     "scip-java installation log");
             BoundedLineReader reader = new BoundedLineReader(
                     new InputStreamReader(input, StandardCharsets.UTF_8), MAX_INSTALL_LOG_LINE_CHARS)) {
            String line;
            while ((line = reader.readLine()) != null) {
                List<Path> candidate = jarList(line.trim());
                if (!candidate.isEmpty()) classpath = candidate;
            }
        }
        if (classpath.isEmpty()) throw new IllegalStateException("scip-java classpath resolution returned no jar");
        return classpath;
    }

    private static List<Path> jarList(String line) {
        if (line.isEmpty()) return List.of();
        List<Path> jars = new ArrayList<>();
        for (String entry : line.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            if (!entry.endsWith(".jar")) return List.of();
            Path jar;
            try {
                jar = Path.of(entry);
            } catch (java.nio.file.InvalidPathException _) {
                return List.of(); // a progress line such as "Downloaded https://.../x.jar" is not a classpath
            }
            if (!jar.isAbsolute()) return List.of();
            jars.add(jar);
        }
        return jars;
    }

    /**
     * Whether the installed classpath is the verified one: its jars match the lock and, off Windows, its launcher
     * matches the listing. An absent, unreadable or altered tree (or one written by an older MINOS, which listed the
     * jars of the Coursier cache by absolute path) is installed again.
     */
    boolean installedClasspathVerifies(ClasspathLock lock) {
        Path listing = scipJavaClasspathFile();
        if (!Files.isRegularFile(listing, LinkOption.NOFOLLOW_LINKS)) return false;
        try {
            ScipJavaClasspath.verify(listing, lock, !CommandLocator.isWindows());
            return true;
        } catch (IOException | RuntimeException _) {
            return false;
        }
    }

    /**
     * Copies the verified jars into MINOS_HOME/tools (the index plan never reads the Coursier cache), verifies the
     * copies, writes the launcher and, last, the listing, then swaps the tree in. The layout is the one of the
     * Windows distribution: jars and listing side by side, the listing naming the jars relative to itself.
     *
     * <p>The files inherit the owner-only access of the private partial directory instead of carrying a protected
     * access list each, as the extracted Windows distribution does: the Windows sandbox grants its read access on the
     * directory, and a protected file would not inherit it. The previous tree is moved aside, not deleted, until the
     * new one is in place, so a failed swap restores it.</p>
     */
    void installClasspath(List<Path> jars, ClasspathLock lock) throws IOException {
        Path destination = scipJavaClasspathRoot();
        Path partial = destination.resolveSibling(destination.getFileName() + ".partial");
        Path previous = destination.resolveSibling(destination.getFileName() + ".previous");
        deleteRecursively(partial);
        PrivateLocalStorage.ensurePrivateDirectory(partial);
        try {
            List<String> names = new ArrayList<>(jars.size());
            List<Path> copies = new ArrayList<>(jars.size());
            for (Path jar : jars) {
                Path fileName = jar.getFileName();
                String name = ScipJavaClasspath.requireJarName(fileName == null ? null : fileName.toString());
                Path copy = partial.resolve(name);
                copyJar(jar, copy);
                names.add(name);
                copies.add(copy);
            }
            lock.verify(copies);
            writeNewFile(partial.resolve(ScipJavaClasspath.LAUNCHER),
                    new ByteArrayInputStream(ScipJavaClasspath.launcherBytes(names)));
            writeNewFile(partial.resolve(CLASSPATH_FILE), new ByteArrayInputStream(ScipJavaClasspath.listingBytes(names)));
            swapIn(partial, destination, previous);
        } catch (IOException | RuntimeException failure) {
            try {
                deleteRecursively(partial);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    /** Replaces {@code destination} by {@code partial}, keeping the previous tree aside until the swap is done. */
    static void swapIn(Path partial, Path destination, Path previous) throws IOException {
        swapIn(partial, destination, previous, ManagedScipProviderRuntimeManager::moveDirectory);
    }

    /** The publication of a directory, a seam of the tests of {@link #swapIn}. */
    @FunctionalInterface
    interface DirectoryMover {
        void move(Path source, Path target) throws IOException;
    }

    static void swapIn(Path partial, Path destination, Path previous, DirectoryMover mover) throws IOException {
        deleteRecursively(previous);
        boolean replacing = Files.exists(destination, LinkOption.NOFOLLOW_LINKS);
        if (replacing) mover.move(destination, previous);
        try {
            mover.move(partial, destination);
        } catch (IOException swapFailure) {
            if (replacing) restore(previous, destination, swapFailure, mover);
            throw swapFailure;
        }
        deleteRecursively(previous);
    }

    private static void restore(Path previous, Path destination, IOException swapFailure, DirectoryMover mover) {
        try {
            mover.move(previous, destination);
        } catch (IOException restoreFailure) {
            swapFailure.addSuppressed(restoreFailure);
        }
    }

    private static void copyJar(Path jar, Path copy) throws IOException {
        try (InputStream input = new BoundedInputStream(
                ConfinedFileOpener.openRegularFileNoFollow(jar), MAX_CLASSPATH_JAR_BYTES, "scip-java classpath jar")) {
            writeNewFile(copy, input);
        }
    }

    /** A new file in a private directory, never through a link, whose access is inherited from the directory. */
    private static void writeNewFile(Path target, InputStream content) throws IOException {
        try (OutputStream output = PrivateLocalStorage.openNewTreeFile(target)) {
            content.transferTo(output);
        }
    }

    /**
     * What the index plan can run: the installed classpath (and, off Windows, its launcher) or, off Windows and only
     * when no classpath is installed, a standalone launcher on the PATH (the one of the Docker image, or one the
     * operator chose). Presence only: the plan verifies the bytes.
     */
    private boolean scipJavaClasspathInstalled() {
        boolean windows = CommandLocator.isWindows();
        Path listing = scipJavaClasspathFile();
        if (Files.isRegularFile(listing, LinkOption.NOFOLLOW_LINKS)) {
            return windows || Files.isRegularFile(listing.resolveSibling(ScipJavaClasspath.LAUNCHER), LinkOption.NOFOLLOW_LINKS);
        }
        return !windows && CommandLocator.find(SCIP_JAVA_ID).isPresent();
    }

    private void installJavaWindowsRuntime() throws IOException {
        Path runtime = scipJavaRuntimeRoot();
        PrivateLocalStorage.ensurePrivateDirectory(runtime);
        copyPackagedResource(WINDOWS_RUNNER_RESOURCE, runtime.resolve(WINDOWS_RUNNER_RESOURCE));
        copyPackagedResource(WINDOWS_PATCH_RESOURCE, runtime.resolve(WINDOWS_PATCH_RESOURCE));
    }

    static void copyPackagedResource(String name, Path destination) throws IOException {
        try (InputStream input = ManagedScipProviderRuntimeManager.class.getResourceAsStream(EmbeddedToolsCatalog.RESOURCE_ROOT + name)) {
            if (input == null) throw new IllegalStateException("packaged scip-java runtime resource is missing: " + name);
            Path partial = destination.resolveSibling(destination.getFileName() + ".partial");
            Files.deleteIfExists(partial);
            writeNewFile(partial, input);
            moveFile(partial, destination);
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
        moveFile(archivePartial, archive);

        int executableEntries = 0;
        try (InputStream input = ConfinedFileOpener.openRegularFileNoFollow(archive); ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory() && entry.getName().toLowerCase(Locale.ROOT).endsWith(".exe")) {
                    executableEntries++;
                    if (executableEntries == 1) writeNewFile(executablePartial, zip);
                }
            }
        }
        if (executableEntries != 1 || !Files.isRegularFile(executablePartial) || Files.size(executablePartial) == 0L) {
            Files.deleteIfExists(executablePartial);
            throw new IllegalStateException("Coursier launcher ZIP did not contain a Windows executable");
        }
        moveFile(executablePartial, destination);
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
        moveFile(archivePartial, archive);

        extractDistribution(archive, root, "apache-maven-" + mavenArtifact().version(),
                MAX_MAVEN_EXTRACTED_BYTES, MAX_MAVEN_ARCHIVE_ENTRIES);
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
        moveFile(archivePartial, archive);

        extractDistribution(archive, root, nodeDistributionId(), MAX_NODE_EXTRACTED_BYTES, MAX_NODE_ARCHIVE_ENTRIES);
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
            moveDirectory(partial, destination);
            origins.record(component.id(), PinnedArtifactSource.Origin.EMBEDDED);
        } finally {
            Files.deleteIfExists(archivePartial);
            deleteRecursively(partial);
        }
    }

    /**
     * An embedded tree that no longer matches the digest stamped when it was seeded, that lost its markers, or that
     * contains a link (symbolic link, junction, any reparse point) is refused: every failure to verify is a refusal,
     * never "not provided". A tree without markers and without an embedded origin is a legacy download, left alone.
     */
    private SeedOutcome verifyInstalledEmbeddedTrees(String providerId) {
        for (String component : CATALOG.provider(providerId).components()) {
            Path tree = switch (component) {
                case TYPESCRIPT_MODULES_ID -> typeScriptRoot();
                case JAVA_CLASSPATH_ID -> scipJavaClasspathRoot();
                default -> null;
            };
            if (tree == null || !Files.isDirectory(tree, LinkOption.NOFOLLOW_LINKS)) continue;
            boolean embedded = origins.origin(component).filter("embedded"::equals).isPresent()
                    || ManagedPolyglotScipRuntimeManager.installSourceMatches(tree, EMBEDDED_TREE_SOURCE);
            if (!embedded) continue;
            String problem = embeddedTreeProblem(tree);
            if (problem != null) {
                return new SeedOutcome(true, List.of("embedded tools were refused: " + component + " " + problem
                        + PinnedArtifactSource.REINSTALL_HINT));
            }
        }
        return null;
    }

    private static String embeddedTreeProblem(Path tree) {
        if (!ManagedPolyglotScipRuntimeManager.installSourceMatches(tree, EMBEDDED_TREE_SOURCE)) {
            return "lost its embedded-origin marker";
        }
        if (containsLink(tree)) return "contains a link or a reparse point";
        if (!ManagedPolyglotScipRuntimeManager.integrityManifestMatches(tree)) return "no longer matches its integrity manifest";
        return null;
    }

    private static boolean containsLink(Path tree) {
        boolean[] found = {false};
        try {
            Files.walkFileTree(tree, new java.nio.file.SimpleFileVisitor<>() {
                @Override
                public java.nio.file.FileVisitResult preVisitDirectory(Path directory, java.nio.file.attribute.BasicFileAttributes attributes) {
                    return flag(attributes);
                }

                @Override
                public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attributes) {
                    return flag(attributes);
                }

                private java.nio.file.FileVisitResult flag(java.nio.file.attribute.BasicFileAttributes attributes) {
                    if (attributes.isSymbolicLink() || attributes.isOther()) {
                        found[0] = true;
                        return java.nio.file.FileVisitResult.TERMINATE;
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | RuntimeException unreadable) {
            return true;
        }
        return found[0];
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
                try (OutputStream output = openExtractedFile(target)) {
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

    /**
     * Extracts a distribution archive whose entries all live under {@code topLevelDirectory}. An earlier extraction
     * may have been interrupted: its tree is removed first, so that every entry is created new and a relaunch succeeds.
     */
    static void extractDistribution(
            Path archive, Path root, String topLevelDirectory, long maxTotalBytes, long maxEntries
    ) throws IOException {
        deleteRecursively(root.resolve(topLevelDirectory));
        extractZipBounded(archive, root, maxTotalBytes, maxEntries);
    }

    /** A file of an archive is new: a name met twice (or twice under case folding) is a defect of the archive. */
    private static OutputStream openExtractedFile(Path target) throws IOException {
        try {
            return PrivateLocalStorage.openNewTreeFile(target);
        } catch (java.nio.file.FileAlreadyExistsException duplicate) {
            throw new IOException("archive has a duplicate entry", duplicate);
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

    /** The PowerShell that will launch the runner: the system one the sandbox trusts, never one found on PATH. */
    static Optional<Path> powerShellExecutable() { return CommandLocator.windowsPowerShell(); }

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

    static void run(List<String> command, Path workingDirectory, Path log, Duration timeout)
            throws IOException, InterruptedException {
        PrivateLocalStorage.ensurePrivateDirectory(log.toAbsolutePath().normalize().getParent());
        ProcessBuilder builder = new ProcessBuilder(command);
        InstallationProcessEnvironment.apply(builder, Map.of());
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

    /** The parent is made private before every publication, so that a new tool folder exists and is owner-only. */
    private static void moveFile(Path source, Path target) throws IOException {
        PrivateLocalStorage.ensurePrivateDirectory(target.toAbsolutePath().normalize().getParent());
        DurableAtomicFile.replace(source, target, "managed tool file installation");
    }

    private static void moveDirectory(Path source, Path target) throws IOException {
        PrivateLocalStorage.ensurePrivateDirectory(target.toAbsolutePath().normalize().getParent());
        DurableAtomicFile.moveDirectory(source, target, "managed tool directory installation");
    }
}
