package com.minos.runtime.local.wsl;

import com.minos.diagnostics.PublicErrorMessages;
import com.minos.io.DurableAtomicFile;
import com.minos.io.FileTreeOperations;
import com.minos.io.PrivateLocalStorage;
import com.minos.io.StaleScratchReclamation;
import com.minos.remote.DistributedArtifactManifest;
import com.minos.remote.DistributedIndexing.Worker;
import com.minos.remote.DistributedIndexing.WorkerIsolation;
import com.minos.remote.DistributedIndexing.WorkerNetworkPolicy;
import com.minos.remote.DistributedIndexing.WorkerRequest;
import com.minos.remote.DistributedIndexing.WorkerResponse;
import com.minos.runtime.local.distributed.DistributedArtifactBundleStore;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Windows side of the WSL2 route: runs the internal worker command of the same jar in a distribution and returns
 * the bundle it wrote.
 *
 * <p>The worker starts in a delegated systemd user scope ({@code systemd-run --user --scope -p Delegate=yes}) named
 * after the run, as a direct command: no shell parses any value, and no login shell or user profile runs. Its
 * settings (designated Maven repository, project JDK, scip-java installation) are read by the worker from the MINOS
 * configuration of the distribution. {@code wsl.exe} is the one of the Windows system directory and receives the
 * allowlisted environment of {@link WslCommandEnvironment}. The request and the bundle travel through a private exchange directory of
 * {@code MINOS_HOME}, which the distribution reaches under {@code /mnt/<drive>}.</p>
 *
 * <p>A worker that exceeds its timeout or is interrupted has its scope stopped, which ends every process of the run
 * in the distribution, and leaves no bundle behind. Diagnostics name the distribution, never a path.</p>
 */
public final class WslIndexWorker implements Worker {

    /** Prefix of the worker identity; the distribution name follows. */
    public static final String WORKER_ID_PREFIX = "wsl:";
    /** Status line prefix written by the internal worker command on its standard output. */
    public static final String STATUS_PREFIX = WslWorkerRequest.PROTOCOL + " ";
    /** Internal command of the jar that runs one worker request; absent from {@code --help}. */
    public static final List<String> INTERNAL_COMMAND = List.of("internal", "wsl-worker");

    static final String UNIT_PREFIX = "minos-wsl-";
    static final String EXCHANGE_DIRECTORY = "wsl-exchange";
    static final String RUN_PREFIX = ".run-";
    static final String BUNDLE_PREFIX = ".bundle-";
    /** Start of the launch command in the distribution: a delegated user scope, then the JVM of the distribution. */
    static final List<String> SCOPE_COMMAND = List.of("systemd-run", "--user", "--scope", "-p", "Delegate=yes",
            "--quiet");
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(30);

    private final Path wslExecutable;
    private final String distribution;
    private final WslCommandRunner runner;
    private final Path exchangeRoot;
    private final String jar;
    private final String mainClass;
    private final Duration timeout;
    private final Function<Path, String> distributionPaths;

    /**
     * What is launched for one run: the system {@code wsl.exe}, the distribution, the installation jar and its entry
     * point, and the time after which the scope of the run is stopped.
     *
     * @param wslExecutable absolute {@code wsl.exe} of the Windows system directory
     * @param distribution  resolved distribution name (validated by {@link WslPrerequisiteProbe#DISTRIBUTION_NAME})
     * @param jar           absolute Windows path of the installation jar run in the distribution
     * @param mainClass     entry point of that jar
     * @param timeout       time after which the scope of the run is stopped
     */
    public record Launch(Path wslExecutable, String distribution, Path jar, String mainClass, Duration timeout) {

        public Launch {
            Objects.requireNonNull(wslExecutable, "wslExecutable");
            if (!wslExecutable.isAbsolute()) {
                throw new IllegalArgumentException("wsl.exe must be an absolute path");
            }
            if (distribution == null || !WslPrerequisiteProbe.DISTRIBUTION_NAME.matcher(distribution).matches()) {
                throw new IllegalArgumentException("invalid WSL distribution name");
            }
            jar = Objects.requireNonNull(jar, "jar").toAbsolutePath().normalize();
            Objects.requireNonNull(mainClass, "mainClass");
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
        }
    }

    /**
     * @param launch    what is launched for one run
     * @param minosHome MINOS_HOME of the Windows installation, which holds the exchange directory
     */
    public WslIndexWorker(Launch launch, WslCommandRunner runner, Path minosHome) {
        this(launch, runner, minosHome, path -> WslPaths.toDistribution(path.toString()));
    }

    /**
     * Test seam: the mapping of a Windows path of this host to its path in the distribution is supplied, so that
     * the route can be exercised with a fake distribution on any host.
     */
    WslIndexWorker(Launch launch, WslCommandRunner runner, Path minosHome, Function<Path, String> distributionPaths) {
        Objects.requireNonNull(launch, "launch");
        this.distributionPaths = Objects.requireNonNull(distributionPaths, "distributionPaths");
        this.wslExecutable = launch.wslExecutable();
        this.distribution = launch.distribution();
        this.runner = Objects.requireNonNull(runner, "runner");
        this.exchangeRoot = Objects.requireNonNull(minosHome, "minosHome").toAbsolutePath().normalize()
                .resolve(EXCHANGE_DIRECTORY);
        this.jar = distributionPaths.apply(launch.jar());
        this.mainClass = launch.mainClass();
        this.timeout = launch.timeout();
    }

    @Override
    public String workerId() {
        return WORKER_ID_PREFIX + distribution;
    }

    @Override
    public WorkerIsolation isolation() {
        return WorkerIsolation.PROCESS_EPHEMERAL_WORKSPACE;
    }

    /** Declared by the distribution, which runs the provider under the Linux sandbox with {@code DENY}. */
    @Override
    public boolean enforcesNetworkDeny() {
        return true;
    }

    String distribution() {
        return distribution;
    }

    @Override
    public WorkerResponse execute(WorkerRequest request) throws IOException {
        Objects.requireNonNull(request, "request");
        if (!request.local() || request.networkPolicy() != WorkerNetworkPolicy.DENY) {
            throw new IllegalArgumentException("the WSL worker runs only local requests with a DENY network policy");
        }
        PrivateLocalStorage.ensurePrivateDirectory(exchangeRoot);
        StaleScratchReclamation.reclaim(exchangeRoot,
                name -> name.startsWith(RUN_PREFIX) || name.startsWith(BUNDLE_PREFIX), Set.of());
        Path exchange = PrivateLocalStorage.createPrivateTempDirectory(exchangeRoot, RUN_PREFIX);
        try {
            WslWorkerRequest workerRequest = workerRequest(request);
            Path requestFile = exchange.resolve(WslWorkerRequest.FILE_NAME);
            PrivateLocalStorage.writePrivateFile(requestFile, workerRequest.encode());
            String unit = UNIT_PREFIX + request.execution().runId();
            WslCommandResult result = launch(unit, requestFile);
            Path bundle = exchange.resolve(WslWorkerRequest.BUNDLE_NAME);
            Optional<String> status = statusLine(result.stdout());
            if (result.exitCode() != 0 || !status.map("OK"::equals).orElse(false)
                    || !Files.isRegularFile(bundle, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("the WSL worker failed in distribution " + distribution + ": "
                        + publicDetail(status.orElse("no status line")));
            }
            Path owned = exchangeRoot.resolve(BUNDLE_PREFIX + UUID.randomUUID() + ".zip");
            DurableAtomicFile.replace(bundle, owned, "WSL worker bundle");
            try {
                DistributedArtifactManifest manifest = DistributedArtifactBundleStore.readManifest(owned);
                return new WorkerResponse(owned, manifest);
            } catch (IOException | RuntimeException unreadable) {
                Files.deleteIfExists(owned);
                throw unreadable;
            }
        } finally {
            FileTreeOperations.deleteRecursively(exchange);
        }
    }

    private WslWorkerRequest workerRequest(WorkerRequest request) {
        var execution = request.execution();
        return new WslWorkerRequest(
                execution.runId(),
                execution.projectId(),
                distributionPaths.apply(execution.registeredProjectRoot()),
                WslArtifactProvenance.portableScope(execution.projectRelativeRoot()),
                execution.selection().language(),
                execution.selection().indexer().id(),
                request.providerVersion(),
                request.projectFingerprint(),
                workerId());
    }

    private WslCommandResult launch(String unit, Path requestFile) throws IOException {
        List<String> command = new ArrayList<>(List.of(wslExecutable.toString(), "-d", distribution, "--exec"));
        command.addAll(SCOPE_COMMAND);
        command.addAll(List.of("--unit=" + unit, "--", "java", "-cp", jar, mainClass));
        command.addAll(INTERNAL_COMMAND);
        command.add(distributionPaths.apply(requestFile));
        try {
            return runner.run(List.copyOf(command), timeout);
        } catch (WslCommandTimeoutException | InterruptedIOException stopped) {
            IllegalStateException failure = new IllegalStateException("the WSL worker did not finish in distribution "
                    + distribution + " within " + timeout.toMinutes() + " min; its scope was stopped");
            stopScope(unit, failure);
            if (stopped instanceof InterruptedIOException) {
                Thread.currentThread().interrupt();
            }
            throw failure;
        } catch (IOException unavailable) {
            throw new IllegalStateException("the WSL worker could not be run in distribution " + distribution
                    + " (" + unavailable.getClass().getSimpleName() + ")");
        }
    }

    /** Stops the scope of the run, which ends every process it holds; a failure is attached, never thrown. */
    private void stopScope(String unit, Exception failure) {
        try {
            WslCommandResult stopped = runner.run(List.of(wslExecutable.toString(), "-d", distribution, "--exec",
                    "systemctl", "--user", "stop", unit + ".scope"), STOP_TIMEOUT);
            if (stopped.exitCode() != 0) {
                failure.addSuppressed(new IllegalStateException("systemctl stop exited with " + stopped.exitCode()));
            }
        } catch (IOException stopFailure) {
            failure.addSuppressed(stopFailure);
        }
    }

    /** The last status line of the worker, without its protocol prefix. */
    static Optional<String> statusLine(byte[] stdout) {
        List<String> statuses = new ArrayList<>();
        for (String line : new String(stdout, StandardCharsets.UTF_8).split("\\R")) {
            if (line.startsWith(STATUS_PREFIX)) {
                statuses.add(line.substring(STATUS_PREFIX.length()).strip());
            }
        }
        return statuses.isEmpty() ? Optional.empty() : Optional.of(statuses.getLast());
    }

    /** A worker detail that names a local path or a credential is withheld. */
    static String publicDetail(String detail) {
        return PublicErrorMessages.looksSensitive(detail)
                ? "its diagnostic names a local path and is withheld; see the provider logs of the distribution"
                : detail;
    }
}
