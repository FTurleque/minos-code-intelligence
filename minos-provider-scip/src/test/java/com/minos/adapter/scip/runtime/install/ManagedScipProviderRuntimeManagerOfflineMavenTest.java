package com.minos.adapter.scip.runtime.install;

import com.minos.adapter.scip.runtime.plan.ScipJavaMavenOffline;
import com.minos.adapter.scip.runtime.plan.ScipJavaMavenOfflineFixtures;
import com.minos.runtime.ProviderRuntimeStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Under Linux, scip-java is ready only when it can build offline: a qualified Maven 3.9.x visible from the sandbox
 * and a valid designated repository. What is missing is a machine prerequisite that names the setting and the
 * state, never a path, and Maven is never run to learn its version.
 */
@EnabledOnOs(OS.LINUX)
class ManagedScipProviderRuntimeManagerOfflineMavenTest {

    private static final String JAVA = ManagedScipProviderRuntimeManager.SCIP_JAVA_ID;

    @TempDir Path home;
    @TempDir Path fixture;

    @Test
    void withoutADesignatedRepositoryScipJavaIsNotReadyAndTheDiagnosticNamesTheSetting() throws Exception {
        ProviderRuntimeStatus status = inspect(ScipJavaMavenOfflineFixtures.designating(fixture, null));

        assertNotEquals(ProviderRuntimeStatus.State.READY, status.state());
        assertTrue(status.diagnostics().stream().anyMatch(diagnostic -> diagnostic.startsWith(ExternalPrerequisite.PREFIX)
                && diagnostic.contains(ScipJavaMavenOffline.REPOSITORY_SETTING) && diagnostic.contains("is not set")),
                status.diagnostics()::toString);
    }

    @Test
    void aRefusedDesignationIsReportedByItsSettingAndStateWithoutItsPath() throws Exception {
        Path settingsFolder = Files.createDirectories(fixture.resolve("folder"));
        Files.writeString(settingsFolder.resolve("settings.xml"), "<settings/>");

        ProviderRuntimeStatus status = inspect(ScipJavaMavenOfflineFixtures.designating(fixture, settingsFolder.toString()));

        assertNotEquals(ProviderRuntimeStatus.State.READY, status.state());
        assertTrue(status.diagnostics().stream().anyMatch(diagnostic -> diagnostic.contains(
                ScipJavaMavenOffline.REPOSITORY_SETTING + " is refused")), status.diagnostics()::toString);
        assertTrue(status.diagnostics().stream().noneMatch(diagnostic -> diagnostic.contains(fixture.toString())),
                status.diagnostics()::toString);
    }

    @Test
    void aMavenOutsideTheQualifiedRangeIsAMachinePrerequisiteAndIsNeverRun() throws Exception {
        ProviderRuntimeStatus status = inspect(ScipJavaMavenOfflineFixtures.withMaven(fixture, "3.8.8"));

        assertNotEquals(ProviderRuntimeStatus.State.READY, status.state());
        assertTrue(status.diagnostics().stream().anyMatch(diagnostic -> diagnostic.startsWith(ExternalPrerequisite.PREFIX)
                && diagnostic.contains(ScipJavaMavenOffline.QUALIFIED_MAVEN_RANGE)), status.diagnostics()::toString);
        assertFalse(Files.exists(fixture.resolve("apache-maven-3.8.8/bin/mvn.executed")), "Maven was executed");
    }

    @Test
    void aQualifiedOfflineBuildAddsNoMavenPrerequisite() throws Exception {
        ProviderRuntimeStatus status = inspect(ScipJavaMavenOfflineFixtures.qualified(fixture));

        assertTrue(status.diagnostics().stream().noneMatch(diagnostic -> diagnostic.contains(
                ScipJavaMavenOffline.REPOSITORY_SETTING) || diagnostic.contains("Maven " + ScipJavaMavenOffline.QUALIFIED_MAVEN_RANGE)
                || diagnostic.contains(ScipJavaMavenOffline.QUALIFIED_MAVEN_RANGE)), status.diagnostics()::toString);
    }

    private ProviderRuntimeStatus inspect(ScipJavaMavenOffline offline) {
        return new ManagedScipProviderRuntimeManager(home, offline).inspect(JAVA);
    }
}
