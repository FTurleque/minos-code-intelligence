package com.minos.bootstrap;

import com.minos.adapter.scip.ScipIndexerCatalog;
import com.minos.application.WslIndexingRoute;
import com.minos.incremental.ProjectFingerprintService;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.runtime.ProviderRuntimeManager;
import com.minos.runtime.local.distributed.DistributedArtifactBundleStore;
import com.minos.runtime.local.process.CommandLocator;
import com.minos.runtime.local.wsl.ProcessWslCommandRunner;
import com.minos.runtime.local.wsl.WslCommandRunner;
import com.minos.runtime.local.wsl.WslIndexWorker;
import com.minos.runtime.local.wsl.WslIndexerExecutor;
import com.minos.runtime.local.wsl.WslJarProbe;
import com.minos.runtime.local.wsl.WslLinuxWorker;
import com.minos.runtime.local.wsl.WslPrerequisiteProbe;
import com.minos.runtime.local.wsl.WslRoutedRuntimes;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.CodeSource;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.zip.ZipFile;

/**
 * The WSL2 route of a Windows installation (ADR SEC-0079): scip-java runs in a distribution through the jar of this
 * installation, and its verified artifact comes back to the single orchestration through routed provider runtimes.
 *
 * <p>The readiness of scip-java is the WSL prerequisite probe: a missing check refuses with its {@code doctor}
 * identifier and action before any run. The route runs only from a packaged installation, whose jar the distribution
 * executes.</p>
 */
final class LocalWslIndexingRoute implements WslIndexingRoute {

    /** Entry point of the installation jar that dispatches the internal worker command. */
    static final String MAIN_CLASS = "com.minos.cli.MinosLauncher";
    /** Provider timeout of scip-java (one hour) plus the time to start the scope and write the bundle. */
    static final Duration WORKER_TIMEOUT = Duration.ofMinutes(75);
    static final String NOT_PACKAGED =
            "the WSL route runs only from a packaged MINOS installation, whose jar the distribution executes";

    private final Path home;
    private final Optional<Path> wslExecutable;
    private final WslCommandRunner runner;
    private final Optional<Path> jar;

    LocalWslIndexingRoute(Path home, Optional<Path> wslExecutable, WslCommandRunner runner, Optional<Path> jar) {
        this.home = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
        this.wslExecutable = Objects.requireNonNull(wslExecutable, "wslExecutable");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.jar = Objects.requireNonNull(jar, "jar");
    }

    /**
     * The route of this host: offered on Windows only, by a packaged installation (the distribution runs its jar),
     * with the system {@code wsl.exe} and real processes. Anywhere else the installation does not offer the route.
     */
    static Optional<WslIndexingRoute> forHost(Path home) {
        Objects.requireNonNull(home, "home");
        if (!CommandLocator.isWindows()) {
            return Optional.empty();
        }
        Optional<Path> jar = installationJar(LocalWslIndexingRoute.class.getProtectionDomain().getCodeSource());
        return jar.map(installation -> new LocalWslIndexingRoute(home,
                WslPrerequisiteProbe.systemWslExecutable(System.getenv()), new ProcessWslCommandRunner(),
                Optional.of(installation)));
    }

    /**
     * The packaged jar this code runs from: a regular {@code .jar} file that also holds the entry point
     * {@link #MAIN_CLASS}. Empty for class directories or a module jar (development, tests), which the distribution
     * could not run.
     */
    static Optional<Path> installationJar(CodeSource source) {
        if (source == null || source.getLocation() == null) {
            return Optional.empty();
        }
        try {
            Path location = Path.of(source.getLocation().toURI());
            if (!Files.isRegularFile(location, LinkOption.NOFOLLOW_LINKS)
                    || !location.getFileName().toString().endsWith(".jar")) {
                return Optional.empty();
            }
            try (ZipFile jar = new ZipFile(location.toFile())) {
                return jar.getEntry(MAIN_CLASS.replace('.', '/') + ".class") == null
                        ? Optional.empty() : Optional.of(location);
            }
        } catch (URISyntaxException | IOException | RuntimeException unusable) {
            return Optional.empty();
        }
    }

    @Override
    public ProviderRuntimeManager runtimes(ProviderRuntimeManager nativeRuntimes, Optional<String> distribution) {
        Objects.requireNonNull(nativeRuntimes, "nativeRuntimes");
        Objects.requireNonNull(distribution, "distribution");
        String version = providerVersion(WslLinuxWorker.ACCEPTED_PROVIDER);
        return new WslRoutedRuntimes(nativeRuntimes, version, () -> readiness(distribution),
                resolved -> executor(version, resolved));
    }

    private WslRoutedRuntimes.Readiness readiness(Optional<String> distribution) {
        if (jar.isEmpty()) {
            return WslRoutedRuntimes.Readiness.refused(NOT_PACKAGED);
        }
        return WslRoutedRuntimes.Readiness.of(new WslPrerequisiteProbe(wslExecutable.orElse(null), runner,
                WslJarProbe.NOT_AVAILABLE).probe(distribution.orElse(null)));
    }

    private IndexerExecutor executor(String version, String distribution) {
        WslIndexWorker worker = new WslIndexWorker(wslExecutable.orElseThrow(), distribution, runner, home,
                jar.orElseThrow(), MAIN_CLASS, WORKER_TIMEOUT);
        try {
            return new WslIndexerExecutor(WslLinuxWorker.ACCEPTED_PROVIDER, version, worker,
                    new DistributedArtifactBundleStore(home), new ProjectFingerprintService(), home);
        } catch (IOException unavailable) {
            throw new UncheckedIOException("the verified artifact store of MINOS_HOME is unavailable", unavailable);
        }
    }

    private static String providerVersion(String providerId) {
        return ScipIndexerCatalog.qualifiedDescriptors().stream()
                .filter(descriptor -> descriptor.id().equals(providerId))
                .map(IndexerDescriptor::version)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no qualified descriptor for " + providerId));
    }
}
