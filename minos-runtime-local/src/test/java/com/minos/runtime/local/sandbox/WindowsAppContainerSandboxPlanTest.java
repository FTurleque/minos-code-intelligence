package com.minos.runtime.local.sandbox;

import com.minos.discovery.ProjectDiscovery.BuildSystem;
import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexerCapability;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;
import com.minos.orchestration.IndexerQualification;
import com.minos.orchestration.IndexingMode;
import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.remote.DistributedIndexing.WorkerNetworkPolicy;
import com.minos.runtime.local.process.CommandLocator;
import com.minos.runtime.local.process.IndexerProcessPlan;
import com.minos.runtime.local.process.ProcessIndexerExecutor;
import com.minos.runtime.local.process.ProcessSandboxCapableIndexerExecutor;
import com.minos.testsupport.LogCapture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the Windows backend writes in the plan it hands to its launcher, and what it refuses to plan.
 * The backend is built directly, so nothing here starts an AppContainer: the plan is the contract the
 * launcher applies, and a path that must not become readable by the sandbox identity must be absent
 * from it. The provider command is never executed.
 */
@EnabledOnOs(OS.WINDOWS)
class WindowsAppContainerSandboxPlanTest {

    private static final String PLAN_FILE = "windows-appcontainer-plan.txt";
    private static final String JOB_OBJECT_LAUNCHER = "windows-job-object-owner-v1.ps1";

    @TempDir
    Path temporary;

    @Test
    void thePlanGrantsTheManagedProviderRootAndExactFilesAndNothingElse() throws Exception {
        Path home = Files.createDirectory(temporary.resolve("home"));
        Path providerRoot = Files.createDirectories(
                home.resolve("tools").resolve("fixture-provider").resolve("1.0.0"));
        Path executable = Files.writeString(
                Files.createDirectory(providerRoot.resolve("bin")).resolve("tool.exe"), "never executed");
        Path runtime = Files.createDirectory(providerRoot.resolve("runtime"));
        Path managedFile = Files.writeString(runtime.resolve("runner.jar"), "managed");
        Path managedDirectory = Files.createDirectory(providerRoot.resolve("conf"));
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Path outsideFile = Files.writeString(outside.resolve("settings.json"), "{}");
        Path working = Files.createDirectory(temporary.resolve("working"));
        Path insideWorking = Files.writeString(working.resolve("input.txt"), "input");
        Path run = Files.createDirectory(temporary.resolve("run"));
        Path missing = temporary.resolve("missing").resolve("nothing.txt");
        List<String> command = List.of(
                executable.toString(),
                "index",
                "--flag=value",
                "bad|name<>?",
                missing.toString(),
                outsideFile.toString(),
                outside.toString(),
                insideWorking.toString(),
                powershell().toString(),
                managedFile.toString(),
                managedDirectory.toString());
        IndexerProcessPlan original = new IndexerProcessPlan(
                command, working, Map.of(), run.resolve("index.scip"), Duration.ofSeconds(30));

        IndexerProcessPlan sandboxed = backend(home).sandboxPlan(original, run);

        Map<String, String> plan = readPlan(run);
        Path realTemporary = temporary.toRealPath();
        assertEquals(command, values(plan, "command"), "provider arguments reach the launcher unchanged");
        assertEquals(List.of(providerRoot.toRealPath(), runtime.toRealPath()),
                under(realTemporary, paths(plan, "read")),
                "only the provider/version root and the directory of a managed file argument are read roots");
        assertEquals(List.of(outsideFile.toRealPath()), under(realTemporary, paths(plan, "readFile")),
                "a file outside the managed tools is granted alone, never with its directory");
        assertEquals(List.of(working.toRealPath(), run.toRealPath()), paths(plan, "write"));
        assertFalse(paths(plan, "read").contains(outside.toRealPath()), "a directory argument grants nothing");
        assertTrue(paths(plan, "read").stream().noneMatch(path -> path.startsWith(systemRoot())),
                "the Windows directory is never granted");
        assertTrue(paths(plan, "readFile").stream().noneMatch(path -> path.startsWith(systemRoot())),
                "a file of the Windows directory is never granted");
        assertEquals("DENY", plan.get("networkPolicy"));
        assertEquals("240", plan.get("jobCpuSeconds"));
        assertEquals(working.toRealPath().toString(), decode(plan.get("working")));
        assertTrue(Path.of(decode(plan.get("recovery"))).startsWith(home.toAbsolutePath().normalize()));

        assertEquals(Map.of(), sandboxed.environment(), "the launcher gets no provider environment");
        assertEquals(original.workingDirectory(), sandboxed.workingDirectory());
        assertEquals(original.generatedArtifact(), sandboxed.generatedArtifact());
        assertEquals(original.timeout(), sandboxed.timeout());
        assertEquals(powershell().toString(), sandboxed.command().get(0));
        assertEquals(run.toRealPath().resolve(PLAN_FILE).toString(),
                sandboxed.command().get(sandboxed.command().indexOf("-Plan") + 1));
    }

    @Test
    void aToolchainVariableGrantsItsOwnRootAndNoOtherValueDoes() throws Exception {
        Path home = Files.createDirectory(temporary.resolve("home"));
        Path working = Files.createDirectory(temporary.resolve("working"));
        Path run = Files.createDirectory(temporary.resolve("run"));
        Path toolchains = Files.createDirectory(temporary.resolve("toolchains"));
        Path projectJdk = Files.createDirectory(toolchains.resolve("project-jdk"));
        Path notAToolchainKey = Files.createDirectory(toolchains.resolve("other-home"));
        Path regularFile = Files.writeString(toolchains.resolve("not-a-directory.txt"), "file");
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("JAVA_HOME", projectJdk.toString());
        environment.put("PROVIDER_HOME", notAToolchainKey.toString());
        environment.put("JDK_HOME", "   ");
        environment.put("DOTNET_ROOT", "relative\\sdk");
        environment.put("CARGO_HOME", toolchains.resolve("missing-cargo").toString());
        environment.put("RUSTUP_HOME", regularFile.toString());
        environment.put("COURSIER_CACHE", powershell().getParent().toString());
        environment.put("MINOS_TEAM_TOKEN", "s3cr3t-value-never-forwarded");
        Path provider = Files.writeString(
                Files.createDirectory(temporary.resolve("provider")).resolve("provider.exe"), "never executed");
        IndexerProcessPlan original = new IndexerProcessPlan(
                List.of(provider.toString(), "index"), working, environment,
                run.resolve("index.scip"), Duration.ofSeconds(30));

        backend(home).sandboxPlan(original, run);

        Map<String, String> plan = readPlan(run);
        assertEquals(List.of(provider.toRealPath()), under(temporary.toRealPath(), paths(plan, "readFile")),
                "a provider executable outside the managed tools is granted alone, never with its directory");
        assertEquals(List.of(projectJdk.toRealPath()), under(temporary.toRealPath(), paths(plan, "read")),
                "only an existing directory named by a toolchain variable becomes a read root");
        assertTrue(paths(plan, "read").stream().noneMatch(path -> path.startsWith(systemRoot())),
                "a toolchain variable pointing into the Windows directory grants nothing");
        Map<String, String> forwarded = environmentOf(plan);
        assertEquals(projectJdk.toString(), forwarded.get("JAVA_HOME"));
        assertNull(forwarded.get("MINOS_TEAM_TOKEN"), "a secret-looking variable is never forwarded");
        assertFalse(forwarded.containsValue("s3cr3t-value-never-forwarded"));
        assertFalse(Files.readString(run.resolve(PLAN_FILE), StandardCharsets.UTF_8).contains(
                Base64.getEncoder().encodeToString("s3cr3t-value-never-forwarded".getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void aToolchainVariableNeverWidensTheSandboxToADriveOrDuplicatesAWriteRoot() throws Exception {
        Path home = Files.createDirectory(temporary.resolve("home"));
        Path working = Files.createDirectory(temporary.resolve("working"));
        Path run = Files.createDirectory(temporary.resolve("run"));
        Path cacheInsideWorking = Files.createDirectory(working.resolve("cache"));
        Path drive = working.toRealPath().getRoot();
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("JDK_HOME", "C:\\bad|name<>");
        environment.put("DOTNET_ROOT", drive.toString());
        environment.put("CARGO_HOME", cacheInsideWorking.toString());
        IndexerProcessPlan original = new IndexerProcessPlan(
                List.of(powershell().toString(), "-NoLogo"), working, environment,
                run.resolve("index.scip"), Duration.ofSeconds(30));

        backend(home).sandboxPlan(original, run, WorkerNetworkPolicy.ALLOW);

        Map<String, String> plan = readPlan(run);
        assertFalse(paths(plan, "read").contains(drive), "a drive root is never a read root");
        assertEquals(List.of(), under(temporary.toRealPath(), paths(plan, "read")),
                "a root already writable is not granted a second time as a read root");
        assertEquals(List.of(working.toRealPath(), run.toRealPath()), paths(plan, "write"));
        assertEquals("ALLOW", plan.get("networkPolicy"));
    }

    @Test
    void anExecutableThatIsItselfTheProviderVersionEntryGrantsNoDirectory() throws Exception {
        Path home = Files.createDirectory(temporary.resolve("home"));
        Path flat = Files.createDirectories(home.resolve("tools").resolve("flat-provider"));
        Path executable = Files.writeString(flat.resolve("tool.exe"), "never executed");
        Path working = Files.createDirectory(temporary.resolve("working"));
        Path run = Files.createDirectory(temporary.resolve("run"));
        IndexerProcessPlan original = new IndexerProcessPlan(
                List.of(executable.toString()), working, Map.of(), run.resolve("index.scip"), Duration.ofSeconds(30));

        backend(home).sandboxPlan(original, run);

        Map<String, String> plan = readPlan(run);
        Path realHome = home.toRealPath();
        // tools\<provider>\<file>: the "version" entry is a file, so there is no directory to grant, and
        // neither the provider directory nor the tools directory is granted in its place.
        assertEquals(List.of(), under(realHome, paths(plan, "read")),
                "a managed path without a version directory must not widen the grant to its parents");
        assertEquals(List.of(), under(realHome, paths(plan, "readFile")));
    }

    @Test
    void aManagedPathThatNamesNoProviderVersionIsRefusedAndNoPlanIsWritten() throws Exception {
        Path home = Files.createDirectory(temporary.resolve("home"));
        Path loose = Files.writeString(Files.createDirectory(home.resolve("tools")).resolve("loose.txt"), "loose");
        Path working = Files.createDirectory(temporary.resolve("working"));
        Path run = Files.createDirectory(temporary.resolve("run"));
        IndexerProcessPlan original = new IndexerProcessPlan(
                List.of(powershell().toString(), loose.toString()), working, Map.of(),
                run.resolve("index.scip"), Duration.ofSeconds(30));
        WindowsAppContainerWorkerSandboxBackend backend = backend(home);

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> backend.sandboxPlan(original, run));

        assertTrue(refusal.getMessage().startsWith("managed provider path does not identify provider/version"),
                refusal.getMessage());
        assertFalse(Files.exists(run.resolve(PLAN_FILE)), "a refused plan is never written");
    }

    @Test
    void anUnresolvableProviderExecutableIsRefusedAndNoPlanIsWritten() throws Exception {
        Path home = Files.createDirectory(temporary.resolve("home"));
        Path working = Files.createDirectory(temporary.resolve("working"));
        Path run = Files.createDirectory(temporary.resolve("run"));
        String unknown = "minos-no-such-provider-" + UUID.randomUUID();
        IndexerProcessPlan byName = new IndexerProcessPlan(
                List.of(unknown), working, Map.of(), run.resolve("index.scip"), Duration.ofSeconds(30));
        IndexerProcessPlan byMissingPath = new IndexerProcessPlan(
                List.of(temporary.resolve("missing-provider.exe").toString()), working, Map.of(),
                run.resolve("index.scip"), Duration.ofSeconds(30));
        WindowsAppContainerWorkerSandboxBackend backend = backend(home);

        IllegalArgumentException unresolved = assertThrows(IllegalArgumentException.class,
                () -> backend.sandboxPlan(byName, run));
        assertThrows(IllegalArgumentException.class, () -> backend.sandboxPlan(byMissingPath, run));

        assertEquals("sandbox provider executable cannot be resolved: " + unknown, unresolved.getMessage());
        assertFalse(Files.exists(run.resolve(PLAN_FILE)), "a refused plan is never written");
    }

    @Test
    void aNullPlanOrNetworkPolicyIsRefused() throws Exception {
        Path working = Files.createDirectory(temporary.resolve("working"));
        Path run = Files.createDirectory(temporary.resolve("run"));
        IndexerProcessPlan original = new IndexerProcessPlan(
                List.of(powershell().toString(), "-NoLogo"), working, Map.of(),
                run.resolve("index.scip"), Duration.ofSeconds(30));
        WindowsAppContainerWorkerSandboxBackend backend = backend(Files.createDirectory(temporary.resolve("home")));

        NullPointerException noPlan = assertThrows(NullPointerException.class,
                () -> backend.sandboxPlan(null, run, WorkerNetworkPolicy.DENY));
        NullPointerException noPolicy = assertThrows(NullPointerException.class,
                () -> backend.sandboxPlan(original, run, null));

        assertEquals("plan", noPlan.getMessage());
        assertEquals("networkPolicy", noPolicy.getMessage());
        assertFalse(Files.exists(run.resolve(PLAN_FILE)), "a refused plan is never written");
    }

    @Test
    void anExecutorWithoutProcessSandboxCapabilityIsRefusedAndNeverRun() throws Exception {
        WindowsAppContainerWorkerSandboxBackend backend = backend(Files.createDirectory(temporary.resolve("home")));
        IndexingExecutionRequest request = executionRequest(Files.createDirectory(temporary.resolve("project")));
        AtomicBoolean executed = new AtomicBoolean();
        IndexerExecutor plain = new IndexerExecutor() {
            @Override
            public String indexerId() {
                return "fixture-provider";
            }

            @Override
            public IndexingArtifact execute(IndexingExecutionRequest ignored) {
                executed.set(true);
                throw new AssertionError("an executor that cannot be sandboxed must never run");
            }
        };

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> backend.execute(plain, request, WorkerNetworkPolicy.DENY));

        assertEquals("qualified OS sandbox requires an executor with explicit process-sandbox capability",
                refusal.getMessage());
        assertFalse(executed.get(), "the provider must not run outside the sandbox");
    }

    @Test
    void executeRefusesNullArgumentsBeforeReachingTheExecutor() throws Exception {
        WindowsAppContainerWorkerSandboxBackend backend = backend(Files.createDirectory(temporary.resolve("home")));
        IndexingExecutionRequest request = executionRequest(Files.createDirectory(temporary.resolve("project")));
        RecordingExecutor executor = new RecordingExecutor(temporary.resolve("artifact.scip"));

        NullPointerException noDelegate = assertThrows(NullPointerException.class,
                () -> backend.execute(null, request, WorkerNetworkPolicy.DENY));
        NullPointerException noRequest = assertThrows(NullPointerException.class,
                () -> backend.execute(executor, null, WorkerNetworkPolicy.DENY));
        NullPointerException noPolicy = assertThrows(NullPointerException.class,
                () -> backend.execute(executor, request, null));

        assertEquals("delegate", noDelegate.getMessage());
        assertEquals("request", noRequest.getMessage());
        assertEquals("networkPolicy", noPolicy.getMessage());
        assertNull(executor.transformer.get(), "a refused execution never reaches the executor");
    }

    @Test
    void executeHandsTheExecutorATrustedLauncherTransformerWithTheReducedWriteQuota() throws Exception {
        Path home = Files.createDirectory(temporary.resolve("home"));
        Path working = Files.createDirectory(temporary.resolve("working"));
        Path run = Files.createDirectory(temporary.resolve("run"));
        WindowsAppContainerWorkerSandboxBackend backend = backend(home);
        IndexingExecutionRequest request = executionRequest(Files.createDirectory(temporary.resolve("project")));
        RecordingExecutor executor = new RecordingExecutor(temporary.resolve("artifact.scip"));

        IndexingArtifact artifact = backend.execute(executor, request, WorkerNetworkPolicy.ALLOW);

        assertSame(executor.artifact, artifact, "the artifact is the one the sandboxed executor produced");
        assertFalse(executor.executedUnsandboxed.get(), "the unsandboxed entry point is never used");
        ProcessIndexerExecutor.ProcessPlanTransformer transformer = executor.transformer.get();
        assertTrue(transformer.isTrustedLauncher());
        assertEquals(Optional.of(WindowsAppContainerWorkerSandboxBackend.EXPLICIT_ROOT_WRITE_QUOTA),
                transformer.providerWriteQuota());
        IndexerProcessPlan sandboxed = transformer.transform(new IndexerProcessPlan(
                List.of(powershell().toString(), "-NoLogo"), working, Map.of(),
                run.resolve("index.scip"), Duration.ofSeconds(30)), run);
        assertEquals("ALLOW", readPlan(run).get("networkPolicy"),
                "the policy given to execute is the one the launcher applies");
        assertTrue(sandboxed.command().contains("-Plan"));
    }

    @Test
    void aBackendWhoseLauncherWouldSitInsideMinosHomeIsNotDiscoveredAndTheRefusalIsLogged() throws Exception {
        // The launcher root itself is given as MINOS_HOME: the script MINOS would run then sits among the data.
        Path launcherRoot = SandboxLauncherScript.materialize(JOB_OBJECT_LAUNCHER).file().getParent().getParent();

        try (LogCapture logs = new LogCapture(WindowsAppContainerWorkerSandboxBackend.class)) {
            Optional<WindowsAppContainerWorkerSandboxBackend> discovered =
                    WindowsAppContainerWorkerSandboxBackend.discover(launcherRoot);

            assertTrue(discovered.isEmpty(), "no backend is offered when its launcher cannot be trusted");
            assertEquals(1, logs.warnings().size(), "the degradation is logged, never silent");
            String warning = logs.warnings().get(0);
            assertTrue(warning.contains("sandbox is unavailable"), warning);
            assertFalse(warning.contains(launcherRoot.toString()), "no absolute path in the journal: " + warning);
        }
        assertFalse(Files.exists(launcherRoot.resolve("sandbox")), "nothing is created in the refused home");
    }

    // ------------------------------------------------------------------------------ helpers

    /** Records what the backend asks of a sandbox-capable executor, without starting any process. */
    private static final class RecordingExecutor implements ProcessSandboxCapableIndexerExecutor {
        private final AtomicReference<ProcessIndexerExecutor.ProcessPlanTransformer> transformer =
                new AtomicReference<>();
        private final AtomicBoolean executedUnsandboxed = new AtomicBoolean();
        private final IndexingArtifact artifact;

        private RecordingExecutor(Path finalArtifact) {
            this.artifact = new IndexingArtifact(Language.JAVA, "fixture-provider", finalArtifact, Path.of(""));
        }

        @Override
        public String indexerId() {
            return "fixture-provider";
        }

        @Override
        public IndexingArtifact execute(IndexingExecutionRequest request) {
            executedUnsandboxed.set(true);
            return artifact;
        }

        @Override
        public IndexingArtifact executeSandboxed(
                IndexingExecutionRequest request,
                ProcessIndexerExecutor.ProcessPlanTransformer planTransformer
        ) {
            transformer.set(planTransformer);
            return artifact;
        }
    }

    private static WindowsAppContainerWorkerSandboxBackend backend(Path home) throws IOException {
        return new WindowsAppContainerWorkerSandboxBackend(home, powershell());
    }

    private static Path powershell() {
        return CommandLocator.windowsPowerShell()
                .orElseThrow(() -> new AssertionError("Windows PowerShell is part of every supported Windows"));
    }

    /** The Windows directory, derived from the PowerShell MINOS resolves there (System32\WindowsPowerShell\v1.0). */
    private static Path systemRoot() {
        return powershell().getParent().getParent().getParent().getParent();
    }

    private static Map<String, String> readPlan(Path run) throws IOException {
        Map<String, String> plan = new LinkedHashMap<>();
        for (String line : Files.readAllLines(run.resolve(PLAN_FILE), StandardCharsets.UTF_8)) {
            int separator = line.indexOf('=');
            if (separator > 0) plan.put(line.substring(0, separator), line.substring(separator + 1));
        }
        return plan;
    }

    private static List<String> values(Map<String, String> plan, String prefix) {
        List<String> values = new ArrayList<>();
        int count = Integer.parseInt(plan.get(prefix + ".count"));
        for (int index = 0; index < count; index++) {
            values.add(decode(plan.get(prefix + "." + index)));
        }
        return values;
    }

    private static List<Path> paths(Map<String, String> plan, String prefix) {
        return values(plan, prefix).stream().map(Path::of).toList();
    }

    private static List<Path> under(Path ancestor, List<Path> paths) {
        return paths.stream().filter(path -> path.startsWith(ancestor)).toList();
    }

    private static Map<String, String> environmentOf(Map<String, String> plan) {
        Map<String, String> environment = new LinkedHashMap<>();
        int count = Integer.parseInt(plan.get("environment.count"));
        for (int index = 0; index < count; index++) {
            environment.put(
                    decode(plan.get("environment." + index + ".key")),
                    decode(plan.get("environment." + index + ".value")));
        }
        return environment;
    }

    private static String decode(String encoded) {
        return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
    }

    private static IndexingExecutionRequest executionRequest(Path projectRoot) {
        IndexerDescriptor descriptor = new IndexerDescriptor(
                "fixture-provider",
                "1.0.0",
                "Fixture provider",
                Set.of(Language.JAVA),
                Set.of(BuildSystem.MAVEN),
                Set.of(IndexerCapability.SYMBOLS),
                IndexerQualification.QUALIFIED,
                1,
                List.of());
        return new IndexingExecutionRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                projectRoot,
                new IndexerSelection(Language.JAVA, descriptor),
                IndexingMode.FULL,
                List.of());
    }
}
