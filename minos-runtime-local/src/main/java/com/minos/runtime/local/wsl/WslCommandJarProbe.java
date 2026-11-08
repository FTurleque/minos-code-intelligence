package com.minos.runtime.local.wsl;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * The jar-level step of the WSL prerequisite probe on a Windows installation: the installation jar runs the probe
 * mode of its internal worker command in the distribution and reports the scip-java checks of the distribution.
 *
 * <p>The command is direct ({@code wsl.exe -d <distribution> --exec java -cp <jar> <main> internal wsl-worker
 * --probe}): no shell parses a value, no login shell or user profile runs, and no systemd scope is needed because
 * the probe only reads files and never runs Maven nor a provider. A probe that cannot start, fails or times out
 * reports nothing, so its checks stay {@link WslCheckState#UNKNOWN}; the operator trace names the exception class or
 * the exit code only.</p>
 */
public final class WslCommandJarProbe implements WslJarProbe {

    /** Bound of one probe: a JVM start and a few file reads in the distribution. */
    static final Duration TIMEOUT = Duration.ofSeconds(90);

    private static final System.Logger LOGGER = System.getLogger(WslCommandJarProbe.class.getName());

    private final Path wslExecutable;
    private final WslCommandRunner runner;
    private final Path jar;
    private final String mainClass;
    private final Function<Path, String> distributionPaths;

    /**
     * @param wslExecutable absolute {@code wsl.exe} of the Windows system directory
     * @param jar           Windows path of the installation jar, on a local drive
     * @param mainClass     entry point of that jar
     */
    public WslCommandJarProbe(Path wslExecutable, WslCommandRunner runner, Path jar, String mainClass) {
        this(wslExecutable, runner, jar, mainClass, path -> WslPaths.toDistribution(path.toString()));
    }

    /** Test seam: the mapping of the jar to its path in the distribution is supplied. */
    WslCommandJarProbe(Path wslExecutable, WslCommandRunner runner, Path jar, String mainClass,
                       Function<Path, String> distributionPaths) {
        this.wslExecutable = Objects.requireNonNull(wslExecutable, "wslExecutable");
        if (!wslExecutable.isAbsolute()) {
            throw new IllegalArgumentException("wsl.exe must be an absolute path");
        }
        this.runner = Objects.requireNonNull(runner, "runner");
        this.jar = Objects.requireNonNull(jar, "jar");
        this.mainClass = Objects.requireNonNull(mainClass, "mainClass");
        this.distributionPaths = Objects.requireNonNull(distributionPaths, "distributionPaths");
    }

    /**
     * @throws IllegalArgumentException when {@code distribution} does not match
     *                                  {@link WslPrerequisiteProbe#DISTRIBUTION_NAME}, or the jar is not on a local
     *                                  drive; no command is run then
     */
    @Override
    public List<WslCheck> probe(String distribution) {
        if (distribution == null || !WslPrerequisiteProbe.DISTRIBUTION_NAME.matcher(distribution).matches()) {
            throw new IllegalArgumentException("invalid WSL distribution name");
        }
        List<String> command = List.of(wslExecutable.toString(), "-d", distribution, "--exec", "java", "-cp",
                distributionPaths.apply(jar), mainClass, "internal", "wsl-worker", WslJarProbeProtocol.PROBE_OPTION);
        WslCommandResult result;
        try {
            result = runner.run(command, TIMEOUT);
        } catch (IOException failure) {
            LOGGER.log(System.Logger.Level.WARNING, "the MINOS jar probe could not run in the distribution ({0})",
                    failure.getClass().getSimpleName());
            return List.of();
        }
        if (result.exitCode() != 0) {
            LOGGER.log(System.Logger.Level.WARNING, "the MINOS jar probe exited with {0} in the distribution",
                    result.exitCode());
            return List.of();
        }
        return WslJarProbeProtocol.decode(result.stdout());
    }
}
