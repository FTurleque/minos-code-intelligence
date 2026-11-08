package com.minos.bootstrap;

import com.minos.application.WslJavaDiagnosis;
import com.minos.application.WslPrerequisiteDiagnostics;
import com.minos.runtime.local.wsl.ProcessWslCommandRunner;
import com.minos.runtime.local.wsl.WslCheck;
import com.minos.runtime.local.wsl.WslCheckState;
import com.minos.runtime.local.wsl.WslCommandRunner;
import com.minos.runtime.local.wsl.WslPrerequisiteProbe;
import com.minos.runtime.local.wsl.WslProbeReport;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * The WSL2 route prerequisites of this Windows host, through the system {@code wsl.exe}. The scip-java checks are run
 * by the installation jar inside the distribution, in the probe mode of its worker command; a run without an
 * installation jar (class directories) reports them unknown.
 *
 * <p>The route is optional: a probe that fails unexpectedly reports every check unknown instead of failing the whole
 * diagnostic, and the operator trace names the exception class only.</p>
 */
final class LocalWslPrerequisiteDiagnostics implements WslPrerequisiteDiagnostics {

    static final String PROBE_FAILED = "rerun minos doctor; the WSL prerequisite probe failed unexpectedly";
    private static final System.Logger LOGGER = System.getLogger(LocalWslPrerequisiteDiagnostics.class.getName());

    private final WslPrerequisiteProbe probe;

    LocalWslPrerequisiteDiagnostics(WslPrerequisiteProbe probe) {
        this.probe = Objects.requireNonNull(probe, "probe");
    }

    /** The real host probe: system {@code wsl.exe}, route environment, and the probe of the installation jar. */
    static LocalWslPrerequisiteDiagnostics forSystem() {
        Optional<Path> wsl = WslPrerequisiteProbe.systemWslExecutable(System.getenv());
        WslCommandRunner runner = new ProcessWslCommandRunner();
        Optional<Path> jar = LocalWslIndexingRoute.installationJar(
                LocalWslPrerequisiteDiagnostics.class.getProtectionDomain().getCodeSource());
        return new LocalWslPrerequisiteDiagnostics(new WslPrerequisiteProbe(wsl.orElse(null), runner,
                LocalWslIndexingRoute.jarProbe(wsl, runner, jar)));
    }

    @Override
    public WslJavaDiagnosis diagnose() {
        WslProbeReport report;
        try {
            report = probe.probe(null);
        } catch (RuntimeException failure) {
            LOGGER.log(System.Logger.Level.WARNING, "WSL prerequisite probe failed ({0})",
                    failure.getClass().getSimpleName());
            return new WslJavaDiagnosis(Optional.empty(), WslCheck.IDENTIFIERS.stream()
                    .map(id -> new WslJavaDiagnosis.Check(id, WslJavaDiagnosis.State.UNKNOWN, PROBE_FAILED))
                    .toList());
        }
        return new WslJavaDiagnosis(report.distribution(), report.checks().stream().map(
                LocalWslPrerequisiteDiagnostics::check).toList());
    }

    private static WslJavaDiagnosis.Check check(WslCheck check) {
        return new WslJavaDiagnosis.Check(check.id(), state(check.state()), check.action());
    }

    static WslJavaDiagnosis.State state(WslCheckState state) {
        return switch (state) {
            case OK -> WslJavaDiagnosis.State.OK;
            case MISSING -> WslJavaDiagnosis.State.MISSING;
            case UNKNOWN -> WslJavaDiagnosis.State.UNKNOWN;
        };
    }
}
