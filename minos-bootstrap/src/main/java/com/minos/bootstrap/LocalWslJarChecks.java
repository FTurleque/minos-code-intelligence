package com.minos.bootstrap;

import com.minos.adapter.scip.runtime.plan.ScipJavaMavenOffline;
import com.minos.adapter.scip.runtime.plan.ScipJavaProjectJdk;
import com.minos.runtime.local.wsl.WslCheck;
import com.minos.runtime.local.wsl.WslJarProbeProtocol;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Probe mode of the WSL worker, run by the installation jar inside the distribution: the scip-java checks of the
 * distribution (Maven of the PATH, project JDK, designated Maven repository), read from the same MINOS settings as
 * the provider runtime of the worker. Maven is never run.
 *
 * <p>An unreadable MINOS configuration reports every check unknown: the probe answers instead of failing, and the
 * worker refuses the run later on the same configuration.</p>
 */
final class LocalWslJarChecks {

    static final String CONFIGURATION_UNREADABLE =
            "the MINOS configuration of the distribution cannot be read; fix it, then rerun";

    private static final System.Logger LOGGER = System.getLogger(LocalWslJarChecks.class.getName());

    private final Supplier<ScipJavaMavenOffline> mavenOffline;
    private final Supplier<ScipJavaProjectJdk> projectJdk;

    LocalWslJarChecks(Supplier<ScipJavaMavenOffline> mavenOffline, Supplier<ScipJavaProjectJdk> projectJdk) {
        this.mavenOffline = Objects.requireNonNull(mavenOffline, "mavenOffline");
        this.projectJdk = Objects.requireNonNull(projectJdk, "projectJdk");
    }

    /** The jar-level checks, in contract order. */
    List<WslCheck> checks() {
        ScipJavaMavenOffline maven;
        ScipJavaProjectJdk jdk;
        try {
            maven = mavenOffline.get();
            jdk = projectJdk.get();
        } catch (IllegalStateException unreadable) {
            LOGGER.log(System.Logger.Level.WARNING, "WSL jar probe: MINOS configuration unreadable ({0})",
                    unreadable.getClass().getSimpleName());
            return WslJarProbeProtocol.unknownChecks(CONFIGURATION_UNREADABLE);
        }
        return List.of(
                WslJarProbeProtocol.check(WslCheck.MAVEN, maven.mavenProblem()),
                WslJarProbeProtocol.check(WslCheck.PROJECT_JDK, jdk.problem()),
                WslJarProbeProtocol.check(WslCheck.MAVEN_REPOSITORY, maven.repositoryProblem()));
    }

    /** Writes one probe line per check on {@code output}; the exit code is {@code 0} once they are written. */
    int probe(Appendable output) throws IOException {
        Objects.requireNonNull(output, "output");
        output.append(WslJarProbeProtocol.encode(checks()));
        return 0;
    }
}
