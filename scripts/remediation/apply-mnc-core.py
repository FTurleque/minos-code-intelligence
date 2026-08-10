from pathlib import Path
import re


def read(path: str) -> str:
    return Path(path).read_text(encoding="utf-8")


def write(path: str, text: str) -> None:
    Path(path).write_text(text, encoding="utf-8", newline="\n")


def replace_once(path: str, old: str, new: str) -> None:
    text = read(path)
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one anchor, found {count}: {old[:120]!r}")
    write(path, text.replace(old, new, 1))


def regex_once(path: str, pattern: str, replacement: str) -> None:
    text = read(path)
    updated, count = re.subn(pattern, replacement, text, count=1, flags=re.S)
    if count != 1:
        raise SystemExit(f"{path}: regex anchor did not match exactly once: {pattern[:120]!r}")
    write(path, updated)


# MNC-01 — atomic registration outcome; unknown implementations fail safe by never claiming creation.
project_registry = "minos-application/src/main/java/com/minos/registry/ProjectRegistry.java"
replace_once(
    project_registry,
    "    RegisteredProject registerProject(Path rootPath, String displayName) throws IOException;\n",
    "    RegisteredProject registerProject(Path rootPath, String displayName) throws IOException;\n\n"
    "    /**\n"
    "     * Atomically reports whether this call created the durable registration. Implementations\n"
    "     * that cannot prove creation return createdByThisCall=false so higher-level rollback never\n"
    "     * deletes a registration that may belong to another concurrent operation.\n"
    "     */\n"
    "    default RegistrationResult registerProjectWithResult(Path rootPath, String displayName) throws IOException {\n"
    "        return new RegistrationResult(registerProject(rootPath, displayName), false);\n"
    "    }\n"
)
replace_once(
    project_registry,
    "    default boolean deleteProject(UUID projectId) throws IOException {\n"
    "        throw new UnsupportedOperationException(\"project deletion is not supported by this registry\");\n"
    "    }\n",
    "    default boolean deleteProject(UUID projectId) throws IOException {\n"
    "        throw new UnsupportedOperationException(\"project deletion is not supported by this registry\");\n"
    "    }\n\n"
    "    record RegistrationResult(RegisteredProject project, boolean createdByThisCall) {\n"
    "        public RegistrationResult {\n"
    "            java.util.Objects.requireNonNull(project, \"project\");\n"
    "        }\n"
    "    }\n"
)

local_registry = "minos-application/src/main/java/com/minos/registry/InterProcessLocalProjectRegistry.java"
replace_once(
    local_registry,
    "    @Override\n"
    "    public RegisteredProject registerProject(Path rootPath, String displayName) throws IOException {\n"
    "        return withLock(() -> delegate.registerProject(rootPath, displayName));\n"
    "    }\n",
    "    @Override\n"
    "    public RegisteredProject registerProject(Path rootPath, String displayName) throws IOException {\n"
    "        return registerProjectWithResult(rootPath, displayName).project();\n"
    "    }\n\n"
    "    @Override\n"
    "    public RegistrationResult registerProjectWithResult(Path rootPath, String displayName) throws IOException {\n"
    "        Objects.requireNonNull(rootPath, \"rootPath\");\n"
    "        return withLock(() -> {\n"
    "            Path canonical = rootPath.toRealPath();\n"
    "            Optional<RegisteredProject> existing = delegate.listProjects().stream()\n"
    "                    .filter(project -> project.rootPath().equals(canonical))\n"
    "                    .findFirst();\n"
    "            RegisteredProject project = delegate.registerProject(canonical, displayName);\n"
    "            return new RegistrationResult(project, existing.isEmpty());\n"
    "        });\n"
    "    }\n"
)

postgres_registry = "minos-storage-postgresql/src/main/java/com/minos/storage/postgresql/PostgresProjectRegistry.java"
regex_once(
    postgres_registry,
    r"    @Override\n    public RegisteredProject registerProject\(Path rootPath, String displayName\) throws IOException \{.*?\n    \}\n\n    @Override\n    public RegisteredWorkspace createWorkspace",
    "    @Override\n"
    "    public RegisteredProject registerProject(Path rootPath, String displayName) throws IOException {\n"
    "        return registerProjectWithResult(rootPath, displayName).project();\n"
    "    }\n\n"
    "    @Override\n"
    "    public RegistrationResult registerProjectWithResult(Path rootPath, String displayName) throws IOException {\n"
    "        Path canonical = canonicalExistingDirectory(rootPath);\n"
    "        if (displayName == null || displayName.isBlank()) {\n"
    "            throw new IllegalArgumentException(\"displayName must not be blank\");\n"
    "        }\n"
    "        RootIdentity root = rootIdentity(canonical);\n"
    "        Instant now = Instant.now();\n"
    "        UUID candidateId = UUID.randomUUID();\n"
    "        try {\n"
    "            return connections.withConnection(connection -> {\n"
    "                try (PreparedStatement statement = connection.prepareStatement(\n"
    "                        \"INSERT INTO projects(id,root_value,root_portable,display_name,workspace_id,created_at,updated_at) \"\n"
    "                                + \"VALUES (?,?,?,?,NULL,?,?) \"\n"
    "                                + \"ON CONFLICT(root_value,root_portable) DO UPDATE SET root_value=projects.root_value \"\n"
    "                                + \"RETURNING id,root_value,root_portable,display_name,workspace_id,created_at,updated_at\")) {\n"
    "                    statement.setObject(1, candidateId);\n"
    "                    statement.setString(2, root.value());\n"
    "                    statement.setBoolean(3, root.portable());\n"
    "                    statement.setString(4, displayName);\n"
    "                    statement.setObject(5, sqlTimestamp(now));\n"
    "                    statement.setObject(6, sqlTimestamp(now));\n"
    "                    try (ResultSet result = statement.executeQuery()) {\n"
    "                        if (!result.next()) {\n"
    "                            throw new SQLException(\"project registration did not return a row\");\n"
    "                        }\n"
    "                        RegisteredProject project = readProject(result);\n"
    "                        return new RegistrationResult(project, candidateId.equals(project.id()));\n"
    "                    }\n"
    "                }\n"
    "            });\n"
    "        } catch (SQLException exception) {\n"
    "            throw io(\"register project\", exception);\n"
    "        }\n"
    "    }\n\n"
    "    @Override\n"
    "    public RegisteredWorkspace createWorkspace"
)

remote_ops = "minos-cli/src/main/java/com/minos/cli/LocalRemoteIndexOperations.java"
replace_once(
    remote_ops,
    "import com.minos.registry.RegisteredProject;\n",
    "import com.minos.registry.ProjectRegistry;\nimport com.minos.registry.RegisteredProject;\n"
)
replace_once(
    remote_ops,
    "            Optional<RegisteredProject> existing = application.projectRegistry().listProjects().stream()\n"
    "                    .filter(candidate -> candidate.rootPath().equals(source.projectRoot()))\n"
    "                    .findFirst();\n"
    "            project = application.projectRegistry().registerProject(source.projectRoot(), displayName);\n"
    "            newlyRegistered = existing.isEmpty();\n",
    "            ProjectRegistry.RegistrationResult registration = application.projectRegistry()\n"
    "                    .registerProjectWithResult(source.projectRoot(), displayName);\n"
    "            project = registration.project();\n"
    "            newlyRegistered = registration.createdByThisCall();\n"
)
# Optional is no longer used in this class after the atomic registration change.
text = read(remote_ops).replace("import java.util.Optional;\n", "")
write(remote_ops, text)

# MNC-02 — Linux sandbox: never expose the complete host root to untrusted provider code.
linux = "minos-runtime-local/src/main/java/com/minos/runtime/LinuxBubblewrapWorkerSandboxBackend.java"
text = read(linux)
text = text.replace("import java.util.List;\n", "import java.util.LinkedHashSet;\nimport java.util.List;\nimport java.util.Set;\n")
text = text.replace(
    " * <p>The host root is mounted read-only. Only the provider working directory, artifact directory\n"
    " * and MINOS run directory are writable. DENY keeps bubblewrap's isolated network namespace;\n",
    " * <p>The host root is never exposed wholesale. Only explicit system runtime roots and concrete\n"
    " * provider command paths are mounted read-only; the workspace, artifact and MINOS run roots are\n"
    " * writable. DENY keeps bubblewrap's isolated network namespace;\n"
)
text = text.replace('                        "LINUX_HOST_ROOT_READ_ONLY",\n', '                        "LINUX_MINIMAL_RUNTIME_READ_ONLY_ALLOWLIST",\n')
text = text.replace(
    "        List<String> sandbox = baseCommand(plan.timeout().plusSeconds(5).toSeconds(), networkPolicy);\n"
    "        sandbox.add(\"--ro-bind\");\n"
    "        sandbox.add(\"/\");\n"
    "        sandbox.add(\"/\");\n",
    "        List<String> sandbox = baseCommand(plan.timeout().plusSeconds(5).toSeconds(), networkPolicy);\n"
    "        addRuntimeReadOnlyBinds(sandbox, plan.command(), networkPolicy);\n"
)
text = text.replace(
    "        List<String> command = baseCommand(5L, WorkerNetworkPolicy.DENY);\n"
    "        command.add(\"--ro-bind\");\n"
    "        command.add(\"/\");\n"
    "        command.add(\"/\");\n",
    "        List<String> command = baseCommand(5L, WorkerNetworkPolicy.DENY);\n"
    "        try {\n"
    "            addRuntimeReadOnlyBinds(command, List.of(\"/bin/true\"), WorkerNetworkPolicy.DENY);\n"
    "        } catch (IOException exception) {\n"
    "            return false;\n"
    "        }\n"
)
anchor = "    private static void addWritableBind(List<String> command, Path directory) {\n"
if text.count(anchor) != 1:
    raise SystemExit("Linux sandbox helper anchor mismatch")
helpers = '''    private static void addRuntimeReadOnlyBinds(
            List<String> command,
            List<String> providerCommand,
            WorkerNetworkPolicy networkPolicy
    ) throws IOException {
        Set<Path> mounted = new LinkedHashSet<>();
        for (String root : List.of("/usr", "/bin", "/lib", "/lib64", "/sbin")) {
            addReadOnlyIfPresent(command, mounted, Path.of(root));
        }
        // Network ALLOW needs public trust/DNS configuration, never the complete /etc tree.
        if (networkPolicy == WorkerNetworkPolicy.ALLOW) {
            for (String value : List.of(
                    "/etc/ssl", "/etc/ca-certificates", "/etc/resolv.conf", "/etc/hosts",
                    "/etc/nsswitch.conf", "/etc/passwd", "/etc/group", "/etc/ld.so.cache")) {
                addReadOnlyIfPresent(command, mounted, Path.of(value));
            }
        }
        for (String argument : providerCommand) {
            try {
                Path candidate = Path.of(argument);
                if (!candidate.isAbsolute() || !Files.exists(candidate)) continue;
                Path real = candidate.toRealPath();
                if (mounted.stream().anyMatch(real::startsWith)) continue;
                addReadOnlyIfPresent(command, mounted, Files.isDirectory(real) ? real : real.getParent());
            } catch (RuntimeException ignored) {
                // Non-path provider arguments stay opaque.
            }
        }
    }

    private static void addReadOnlyIfPresent(List<String> command, Set<Path> mounted, Path candidate)
            throws IOException {
        if (candidate == null || !Files.exists(candidate)) return;
        Path real = candidate.toRealPath();
        if (!mounted.add(real)) return;
        command.add("--ro-bind");
        command.add(real.toString());
        command.add(real.toString());
    }

'''
text = text.replace(anchor, helpers + anchor, 1)
if 'sandbox.add("/");' in text:
    raise SystemExit("Linux sandbox still contains host-root bind")
write(linux, text)

# MNC-04 — blocking OS lease acquisition must not hold the global JVM monitor.
def patch_lease_file(path: str) -> None:
    text = read(path)
    if "import java.util.concurrent.locks.ReentrantLock;" not in text:
        insert = "import java.util.concurrent.locks.ReentrantLock;\n"
        if "import java.util.zip.ZipEntry;\n" in text:
            text = text.replace("import java.util.zip.ZipEntry;\n", insert + "import java.util.zip.ZipEntry;\n", 1)
        else:
            text = text.replace("import java.util.stream.Collectors;\n", "import java.util.stream.Collectors;\n" + insert, 1) if "import java.util.stream.Collectors;\n" in text else text
            if insert not in text:
                # JGit materializer: place before class javadocs after java.util imports.
                marker = "import java.util.concurrent.TimeUnit;\n"
                if marker in text:
                    text = text.replace(marker, marker + insert, 1)
                else:
                    marker = "import java.util.UUID;\n"
                    if marker in text:
                        text = text.replace(marker, marker + insert, 1)
    field_anchor = "    private final Object leaseMonitor = new Object();\n"
    if field_anchor not in text:
        raise SystemExit(f"{path}: lease monitor field missing")
    text = text.replace(
        field_anchor,
        "    private static final int LEASE_STRIPE_COUNT = 64;\n"
        + field_anchor
        + "    private final ReentrantLock[] leaseStripes = createLeaseStripes();\n",
        1,
    )
    pattern = r"    private void acquireLease\(String cacheKey\) throws IOException \{.*?\n    \}\n\n    private void releaseLease"
    replacement = '''    private void acquireLease(String cacheKey) throws IOException {
        ReentrantLock stripe = leaseStripe(cacheKey);
        stripe.lock();
        try {
            synchronized (leaseMonitor) {
                LeaseState existing = activeLeases.get(cacheKey);
                if (existing != null) {
                    existing.references++;
                    return;
                }
            }
            Path leaseFile = leasesRoot.resolve(cacheKey + ".lease");
            FileChannel channel = FileChannel.open(leaseFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try {
                // Deliberately outside leaseMonitor: another process may hold this lock indefinitely.
                FileLock lock = channel.lock();
                synchronized (leaseMonitor) {
                    activeLeases.put(cacheKey, new LeaseState(channel, lock));
                }
            } catch (IOException | RuntimeException exception) {
                channel.close();
                throw exception;
            }
        } finally {
            stripe.unlock();
        }
    }

    private ReentrantLock leaseStripe(String cacheKey) {
        return leaseStripes[Math.floorMod(cacheKey.hashCode(), leaseStripes.length)];
    }

    private static ReentrantLock[] createLeaseStripes() {
        ReentrantLock[] stripes = new ReentrantLock[LEASE_STRIPE_COUNT];
        for (int index = 0; index < stripes.length; index++) stripes[index] = new ReentrantLock();
        return stripes;
    }

    private void releaseLease'''
    text, count = re.subn(pattern, replacement, text, count=1, flags=re.S)
    if count != 1:
        raise SystemExit(f"{path}: acquireLease block mismatch")
    write(path, text)

patch_lease_file("minos-runtime-local/src/main/java/com/minos/runtime/DistributedArtifactBundleStore.java")
patch_lease_file("minos-integration-git/src/main/java/com/minos/git/JGitRemoteRepositoryMaterializer.java")

# MNC-05 — explicit ownership/close lifecycle for Path-based reusable facades.
runner = "minos-cli/src/main/java/com/minos/cli/MinosCliRunner.java"
replace_once(
    runner,
    "        return run(MinosApplication.open(home), arguments, output, error);\n",
    "        try (MinosApplication application = MinosApplication.open(home)) {\n"
    "            return run(application, arguments, output, error);\n"
    "        }\n"
)

autonomous = "minos-cli/src/main/java/com/minos/cli/LocalAutonomousIndexOperations.java"
text = read(autonomous)
text = text.replace("public final class LocalAutonomousIndexOperations implements AutonomousIndexOperations {",
                    "public final class LocalAutonomousIndexOperations implements AutonomousIndexOperations, AutoCloseable {")
text = text.replace("    private final MinosApplication application;\n",
                    "    private final MinosApplication application;\n    private final MinosApplication ownedApplication;\n", 1)
text = text.replace(
    "    public LocalAutonomousIndexOperations(Path minosHome) throws IOException { this(MinosApplication.open(minosHome)); }\n\n"
    "    public LocalAutonomousIndexOperations(MinosApplication application) {\n"
    "        this(application, UnaryOperator.identity());\n"
    "    }\n\n"
    "    public LocalAutonomousIndexOperations(\n"
    "            MinosApplication application,\n"
    "            UnaryOperator<IndexerExecutor> executorDecorator\n"
    "    ) {\n"
    "        this.application = Objects.requireNonNull(application, \"application\");\n",
    "    public LocalAutonomousIndexOperations(Path minosHome) throws IOException {\n"
    "        this(MinosApplication.open(minosHome), UnaryOperator.identity(), true);\n"
    "    }\n\n"
    "    public LocalAutonomousIndexOperations(MinosApplication application) {\n"
    "        this(application, UnaryOperator.identity(), false);\n"
    "    }\n\n"
    "    public LocalAutonomousIndexOperations(\n"
    "            MinosApplication application,\n"
    "            UnaryOperator<IndexerExecutor> executorDecorator\n"
    "    ) {\n"
    "        this(application, executorDecorator, false);\n"
    "    }\n\n"
    "    private LocalAutonomousIndexOperations(\n"
    "            MinosApplication application,\n"
    "            UnaryOperator<IndexerExecutor> executorDecorator,\n"
    "            boolean ownsApplication\n"
    "    ) {\n"
    "        this.application = Objects.requireNonNull(application, \"application\");\n"
    "        this.ownedApplication = ownsApplication ? this.application : null;\n",
    1,
)
if "this.ownedApplication" not in text:
    raise SystemExit("LocalAutonomousIndexOperations constructor patch failed")
text = text[:-2] + '''

    @Override
    public void close() throws IOException {
        if (ownedApplication != null) ownedApplication.close();
    }
}
'''
write(autonomous, text)

project_ops = "minos-application/src/main/java/com/minos/application/LocalProjectOperations.java"
text = read(project_ops)
text = text.replace("public final class LocalProjectOperations implements ProjectOperations {",
                    "public final class LocalProjectOperations implements ProjectOperations, AutoCloseable {")
text = text.replace("    private final ProjectRegistry registry;\n",
                    "    private final MinosApplication ownedApplication;\n    private final ProjectRegistry registry;\n", 1)
text = text.replace(
    "    public LocalProjectOperations(Path home) throws IOException { this(MinosApplication.open(home)); }\n\n"
    "    public LocalProjectOperations(MinosApplication application) {\n"
    "        MinosApplication value = Objects.requireNonNull(application, \"application\");\n",
    "    public LocalProjectOperations(Path home) throws IOException {\n"
    "        this(MinosApplication.open(home), true);\n"
    "    }\n\n"
    "    public LocalProjectOperations(MinosApplication application) {\n"
    "        this(application, false);\n"
    "    }\n\n"
    "    private LocalProjectOperations(MinosApplication application, boolean ownsApplication) {\n"
    "        MinosApplication value = Objects.requireNonNull(application, \"application\");\n"
    "        this.ownedApplication = ownsApplication ? value : null;\n",
    1,
)
if "this.ownedApplication" not in text:
    raise SystemExit("LocalProjectOperations constructor patch failed")
text = text[:-2] + '''

    @Override
    public void close() throws IOException {
        if (ownedApplication != null) ownedApplication.close();
    }
}
'''
write(project_ops, text)

mcp_tools = "minos-mcp/src/main/java/com/minos/mcp/MinosMcpTools.java"
text = read(mcp_tools)
text = text.replace("public final class MinosMcpTools {", "public final class MinosMcpTools implements AutoCloseable {")
text = text.replace("    private final MinosMcpBackend backend;\n",
                    "    private final MinosMcpBackend backend;\n    private final MinosApplication ownedApplication;\n", 1)
text = text.replace(
    "        try {\n"
    "            this.backend = new MinosApplicationMcpBackend(MinosApplication.open(normalizedHome));\n"
    "        } catch (IOException exception) {\n",
    "        try {\n"
    "            this.ownedApplication = MinosApplication.open(normalizedHome);\n"
    "            this.backend = new MinosApplicationMcpBackend(this.ownedApplication);\n"
    "        } catch (IOException exception) {\n",
    1,
)
text = text.replace(
    "    MinosMcpTools(MinosMcpBackend backend) {\n"
    "        this.backend = Objects.requireNonNull(backend, \"backend\");\n"
    "    }\n",
    "    MinosMcpTools(MinosMcpBackend backend) {\n"
    "        this.backend = Objects.requireNonNull(backend, \"backend\");\n"
    "        this.ownedApplication = null;\n"
    "    }\n",
    1,
)
text = text[:-2] + '''

    @Override
    public void close() throws IOException {
        if (ownedApplication != null) ownedApplication.close();
    }
}
'''
write(mcp_tools, text)

print("MNC core remediation staged")
