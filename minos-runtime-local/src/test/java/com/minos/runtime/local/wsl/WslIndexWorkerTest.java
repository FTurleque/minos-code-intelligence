package com.minos.runtime.local.wsl;

import com.minos.discovery.ProjectDiscovery.BuildSystem;
import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.incremental.ProjectFingerprintService;
import com.minos.orchestration.InMemoryIndexStateStore;
import com.minos.orchestration.IndexerCapability;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexerNegotiationResult;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;
import com.minos.orchestration.IndexerQualification;
import com.minos.orchestration.IndexingLifecycleService;
import com.minos.orchestration.IndexingMode;
import com.minos.orchestration.IndexingRun;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotPromoter;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotStager;
import com.minos.remote.DistributedArtifactManifest;
import com.minos.remote.DistributedIndexing.WorkerIsolation;
import com.minos.runtime.local.distributed.DistributedArtifactBundleStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Windows side of the WSL2 route against a fake distribution: import by staging and promotion, an import that
 * fails keeps the previous snapshot, a worker past its timeout has its scope stopped and leaves no bundle, and no
 * diagnostic names an absolute path (indexation-java-via-wsl).
 */
class WslIndexWorkerTest {

    private static final String PROVIDER = "scip-java";
    private static final String VERSION = "0.13.2";
    private static final String DISTRIBUTION = "Ubuntu-24.04";

    @TempDir Path temp;
    private Path home;
    private Path project;
    private Path wsl;
    private Path jar;
    private FakeDistribution distribution;

    @BeforeEach
    void layout() throws IOException {
        home = Files.createDirectories(temp.resolve("home"));
        project = Files.createDirectories(temp.resolve("project"));
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        wsl = Files.writeString(temp.resolve("wsl.exe"), "");
        jar = Files.writeString(temp.resolve("minos.jar"), "");
        distribution = new FakeDistribution(Files.createDirectories(temp.resolve("distribution-home")));
    }

    @Test
    void aConformingArtifactIsImportedByStagingAndPromotionAndNothingStaysInTheExchange() throws Exception {
        AtomicReference<String> active = new AtomicReference<>("snapshot-0");
        List<String> stagedFrom = new ArrayList<>();
        WslIndexerExecutor executor = executor();

        IndexingRun run = lifecycle(executor, active, stagedFrom, false).execute(UUID.randomUUID(), project, negotiation());

        assertEquals(IndexingRun.Status.SUCCEEDED, run.status());
        assertEquals("snapshot-1", active.get(), "the verified artifact is promoted");
        assertEquals(List.of("index-of-the-distribution"), stagedFrom);
        assertTrue(exchangeIsEmpty(), "request and bundle are removed after the import");
        List<String> launch = distribution.commands.getFirst();
        assertEquals(List.of(wsl.toString(), "-d", DISTRIBUTION, "--exec", "systemd-run", "--user", "--scope", "-p",
                "Delegate=yes", "--quiet"), launch.subList(0, 10));
        assertTrue(launch.get(10).startsWith("--unit=" + WslIndexWorker.UNIT_PREFIX), launch.get(10));
        assertEquals(List.of("--", "java", "-cp"), launch.subList(11, 14));
        assertEquals(List.of("com.minos.cli.MinosLauncher", "internal", "wsl-worker"), launch.subList(15, 18));
        assertFalse(launch.contains("sh"), "no shell parses the launch");
        assertFalse(launch.contains("-lc"), "no login shell runs");
        WslWorkerRequest request = distribution.requests.getFirst();
        assertTrue(request.projectRoot().startsWith("/mnt/"), "the worker names the project by its mount");
        assertFalse(distribution.requestTexts.getFirst().contains(project.toString()),
                "the request carries no path of the Windows host");
        assertEquals(WslIndexWorker.WORKER_ID_PREFIX + DISTRIBUTION, request.workerId());
    }

    @Test
    void anImportThatFailsLeavesThePreviousSnapshotActive() throws Exception {
        AtomicReference<String> active = new AtomicReference<>("snapshot-0");
        WslIndexerExecutor executor = executor();

        IndexingRun run = lifecycle(executor, active, new ArrayList<>(), true)
                .execute(UUID.randomUUID(), project, negotiation());

        assertEquals(IndexingRun.Status.FAILED, run.status());
        assertEquals("snapshot-0", active.get(), "nothing is promoted after a failed import");
        assertTrue(exchangeIsEmpty());
    }

    @Test
    void aWorkerPastItsTimeoutHasItsScopeStoppedAndLeavesNoArtifact() throws Exception {
        distribution.behaviour = Behaviour.TIMEOUT;
        WslIndexerExecutor executor = executor();
        IndexingExecutionRequest request = request();

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> executor.execute(request));

        assertTrue(failure.getMessage().contains("its scope was stopped"), failure.getMessage());
        assertNoAbsolutePath(failure.getMessage());
        List<String> stop = distribution.commands.getLast();
        assertEquals(List.of(wsl.toString(), "-d", DISTRIBUTION, "--exec", "systemctl", "--user", "stop",
                WslIndexWorker.UNIT_PREFIX + request.runId() + ".scope"), stop);
        assertTrue(exchangeIsEmpty(), "no partial artifact stays in the Windows installation");
    }

    @Test
    void anInterruptedWorkerHasItsScopeStoppedAndKeepsTheInterruption() throws Exception {
        distribution.behaviour = Behaviour.INTERRUPTED;
        distribution.stopExitCode = 5;
        WslIndexerExecutor executor = executor();
        IndexingExecutionRequest request = request();

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> executor.execute(request));

        assertTrue(Thread.interrupted(), "the interruption is restored");
        assertTrue(failure.getMessage().contains("its scope was stopped"));
        assertEquals(1, failure.getSuppressed().length, "a failed stop is attached, never thrown");
        assertTrue(exchangeIsEmpty());
    }

    @Test
    void aProviderFailureInTheDistributionIsReportedWithoutAnyAbsolutePath() throws Exception {
        distribution.behaviour = Behaviour.PROVIDER_FAILURE;
        WslIndexerExecutor executor = executor();
        IndexingExecutionRequest request = request();

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> executor.execute(request));

        assertTrue(failure.getMessage().startsWith("the WSL worker failed in distribution " + DISTRIBUTION),
                failure.getMessage());
        assertNoAbsolutePath(failure.getMessage());
        assertTrue(exchangeIsEmpty());
    }

    @Test
    void aWorkerThatReportsSuccessWithoutABundleOrThatCannotStartFails() throws Exception {
        distribution.behaviour = Behaviour.NO_BUNDLE;
        WslIndexerExecutor executor = executor();
        IndexingExecutionRequest request = request();
        IllegalStateException missing = assertThrows(IllegalStateException.class, () -> executor.execute(request));
        assertTrue(missing.getMessage().contains("the WSL worker failed"), missing.getMessage());

        distribution.behaviour = Behaviour.UNAVAILABLE;
        IllegalStateException unavailable = assertThrows(IllegalStateException.class, () -> executor.execute(request()));
        assertEquals("the WSL worker could not be run in distribution " + DISTRIBUTION + " (IOException)",
                unavailable.getMessage());
        assertTrue(exchangeIsEmpty());
    }

    @Test
    void theWorkerRefusesAnInvalidConfigurationOrARemoteRequest() {
        assertThrows(IllegalArgumentException.class, () -> worker("bad name;rm"));
        assertThrows(IllegalArgumentException.class, () -> new WslIndexWorker(Path.of("wsl.exe"), DISTRIBUTION,
                distribution, home, jar, "Main", Duration.ofMinutes(1)));
        assertThrows(IllegalArgumentException.class, () -> new WslIndexWorker(wsl, DISTRIBUTION, distribution, home,
                jar, "Main", Duration.ZERO, distribution::map));
        WslIndexWorker worker = worker(DISTRIBUTION);
        assertTrue(worker.enforcesNetworkDeny());
        assertEquals(WorkerIsolation.PROCESS_EPHEMERAL_WORKSPACE, worker.isolation());
        assertEquals(DISTRIBUTION, worker.distribution());
        assertThrows(IllegalArgumentException.class, () -> worker.execute(
                new com.minos.remote.DistributedIndexing.WorkerRequest(request(), VERSION,
                        "https://github.com/acme/demo", "a".repeat(40),
                        com.minos.remote.DistributedIndexing.WorkerNetworkPolicy.DENY)));
    }

    @Test
    void statusLinesAreReadFromTheLastProtocolLineAndPathsAreWithheld() {
        byte[] output = ("noise\n" + WslIndexWorker.STATUS_PREFIX + "FAILED first\n"
                + WslIndexWorker.STATUS_PREFIX + "OK\n").getBytes(StandardCharsets.UTF_8);
        assertEquals(Optional.of("OK"), WslIndexWorker.statusLine(output));
        assertEquals(Optional.empty(), WslIndexWorker.statusLine("nothing".getBytes(StandardCharsets.UTF_8)));
        assertNoAbsolutePath(WslIndexWorker.publicDetail("FAILED build in /home/user/project"));
        assertEquals("FAILED build", WslIndexWorker.publicDetail("FAILED build"));
    }

    private WslIndexWorker worker(String name) {
        return new WslIndexWorker(wsl, name, distribution, home, jar, "com.minos.cli.MinosLauncher",
                Duration.ofMinutes(5), distribution::map);
    }

    private WslIndexerExecutor executor() throws IOException {
        return new WslIndexerExecutor(PROVIDER, VERSION, worker(DISTRIBUTION), new DistributedArtifactBundleStore(home),
                new ProjectFingerprintService(), home);
    }

    private IndexingLifecycleService lifecycle(WslIndexerExecutor executor, AtomicReference<String> active,
                                               List<String> stagedFrom, boolean failImport) {
        SnapshotStager stager = request -> {
            for (var artifact : request.artifacts()) {
                stagedFrom.add(Files.readString(artifact.finalArtifact()));
            }
            if (failImport) {
                throw new IllegalStateException("import refused");
            }
            return "snapshot-1";
        };
        SnapshotPromoter promoter = (projectId, runId, staged) -> active.set(staged);
        return new IndexingLifecycleService(List.of(executor), stager, promoter, new InMemoryIndexStateStore());
    }

    private IndexerNegotiationResult negotiation() {
        return new IndexerNegotiationResult(List.of(new IndexerSelection(Language.JAVA, descriptor())), Set.of(),
                List.of());
    }

    private IndexingExecutionRequest request() {
        return new IndexingExecutionRequest(UUID.randomUUID(), UUID.randomUUID(), project, project, Path.of(""),
                new IndexerSelection(Language.JAVA, descriptor()), IndexingMode.FULL, List.of(), Optional.empty());
    }

    private static IndexerDescriptor descriptor() {
        return new IndexerDescriptor(PROVIDER, VERSION, PROVIDER, Set.of(Language.JAVA), Set.of(BuildSystem.MAVEN),
                Set.of(IndexerCapability.SYMBOLS), IndexerQualification.QUALIFIED, 1, List.of());
    }

    private boolean exchangeIsEmpty() throws IOException {
        Path exchange = home.resolve(WslIndexWorker.EXCHANGE_DIRECTORY);
        if (!Files.exists(exchange)) {
            return true;
        }
        try (Stream<Path> entries = Files.list(exchange)) {
            return entries.findAny().isEmpty();
        }
    }

    private static void assertNoAbsolutePath(String message) {
        assertFalse(message.contains(":\\"), message);
        assertFalse(message.contains("/mnt/"), message);
        assertFalse(message.contains("/home/"), message);
    }

    private enum Behaviour { OK, TIMEOUT, INTERRUPTED, PROVIDER_FAILURE, NO_BUNDLE, UNAVAILABLE }

    /** A WSL distribution that runs the worker protocol in process, reached through mapped paths. */
    private final class FakeDistribution implements WslCommandRunner {
        private final Map<String, Path> mounts = new HashMap<>();
        private final List<List<String>> commands = new ArrayList<>();
        private final List<WslWorkerRequest> requests = new ArrayList<>();
        private final List<String> requestTexts = new ArrayList<>();
        private final Path distributionHome;
        private Behaviour behaviour = Behaviour.OK;
        private int stopExitCode;

        private FakeDistribution(Path distributionHome) {
            this.distributionHome = distributionHome;
        }

        String map(Path windowsPath) {
            String mounted = "/mnt/c/mapped/" + mounts.size();
            mounts.put(mounted, windowsPath);
            return mounted;
        }

        @Override
        public WslCommandResult run(List<String> command, Duration timeout) throws IOException {
            commands.add(List.copyOf(command));
            if (command.contains("systemctl")) {
                return new WslCommandResult(stopExitCode, new byte[0]);
            }
            switch (behaviour) {
                case TIMEOUT -> throw new WslCommandTimeoutException("wsl.exe command timed out");
                case INTERRUPTED -> throw new InterruptedIOException("wsl.exe command interrupted");
                case UNAVAILABLE -> throw new IOException("cannot start " + temp);
                default -> { }
            }
            Path requestFile = mounts.get(command.getLast());
            String text = Files.readString(requestFile);
            requestTexts.add(text);
            WslWorkerRequest request = WslWorkerRequest.decode(text.getBytes(StandardCharsets.UTF_8));
            requests.add(request);
            if (behaviour == Behaviour.PROVIDER_FAILURE) {
                return result(1, "FAILED scip-java: Maven build failed in /home/user/.minos/runs/x");
            }
            if (behaviour == Behaviour.OK) {
                Path artifact = Files.writeString(temp.resolve("distribution-" + UUID.randomUUID() + ".scip"),
                        "index-of-the-distribution");
                DistributedArtifactManifest manifest = DistributedArtifactManifest.localWsl(request.runId(),
                        request.projectId(), request.projectRelativeRoot(), request.projectFingerprint(),
                        request.language(), request.providerId(), request.providerVersion(), request.workerId(),
                        WorkerIsolation.PROCESS_EPHEMERAL_WORKSPACE, Instant.now(), Instant.now(),
                        Files.size(artifact), DistributedArtifactBundleStore.sha256(artifact));
                new DistributedArtifactBundleStore(distributionHome).createBundle(
                        requestFile.resolveSibling(WslWorkerRequest.BUNDLE_NAME), manifest, artifact);
            }
            return result(0, "OK");
        }

        private WslCommandResult result(int code, String status) {
            return new WslCommandResult(code, ("starting\n" + WslIndexWorker.STATUS_PREFIX + status + "\n")
                    .getBytes(StandardCharsets.UTF_8));
        }
    }
}
