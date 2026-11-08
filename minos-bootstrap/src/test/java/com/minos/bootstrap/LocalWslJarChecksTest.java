package com.minos.bootstrap;

import com.minos.adapter.scip.runtime.plan.ScipJavaMavenOffline;
import com.minos.adapter.scip.runtime.plan.ScipJavaProjectJdk;
import com.minos.application.WslIndexingWorker;
import com.minos.runtime.local.wsl.WslCheck;
import com.minos.runtime.local.wsl.WslCheckState;
import com.minos.runtime.local.wsl.WslJarProbeProtocol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The probe mode of the worker in the distribution: the scip-java checks, one line each, never a path. */
class LocalWslJarChecksTest {

    @TempDir Path temp;

    @Test
    void eachScipJavaPrerequisiteBecomesItsOwnCheck() throws Exception {
        Path jdk = Files.createDirectories(temp.resolve("jdk").resolve("bin"));
        Files.writeString(jdk.resolve("javac"), "");
        LocalWslJarChecks checks = new LocalWslJarChecks(
                () -> ScipJavaMavenOffline.configured(null, temp.resolve("home"), Optional::empty),
                () -> ScipJavaProjectJdk.configured(jdk.getParent().toString()));

        List<WslCheck> result = checks.checks();

        assertEquals(WslJarProbeProtocol.CHECKS, result.stream().map(WslCheck::id).toList());
        assertEquals(WslCheckState.MISSING, result.get(0).state(), "no Maven on the PATH");
        assertEquals(new WslCheck(WslCheck.PROJECT_JDK, WslCheckState.OK, ""), result.get(1));
        assertEquals(WslCheckState.MISSING, result.get(2).state(), "no designated repository");
        assertTrue(result.get(2).action().contains(ScipJavaMavenOffline.REPOSITORY_SETTING), result.get(2).action());
    }

    @Test
    void theProbeWritesOneLinePerCheckAndNoPath() throws Exception {
        LocalWslJarChecks checks = new LocalWslJarChecks(
                () -> ScipJavaMavenOffline.configured(null, temp.resolve("home"), Optional::empty),
                () -> ScipJavaProjectJdk.configured(temp.resolve("no-jdk").toString()));
        StringBuilder output = new StringBuilder();

        int code = checks.probe(output);

        assertEquals(0, code);
        assertEquals(3, output.toString().lines().count(), output.toString());
        assertFalse(output.toString().contains(temp.toString()), output.toString());
        List<WslCheck> read = WslJarProbeProtocol.decode(output.toString().getBytes(StandardCharsets.UTF_8));
        assertEquals(checks.checks(), read);
        assertTrue(read.get(1).action().contains(ScipJavaProjectJdk.SETTING), read.get(1).action());
    }

    @Test
    void theWindowsProbeAndTheWorkerCommandAgreeOnTheProbeOption() {
        assertEquals(WslIndexingWorker.PROBE_OPTION, WslJarProbeProtocol.PROBE_OPTION);
    }

    @Test
    void anUnreadableConfigurationLeavesEveryCheckUnknownInsteadOfFailing() {
        LocalWslJarChecks checks = new LocalWslJarChecks(() -> {
            throw new IllegalStateException("MINOS runtime configuration cannot be read");
        }, () -> ScipJavaProjectJdk.configured(null));

        List<WslCheck> result = checks.checks();

        assertEquals(3, result.size());
        assertTrue(result.stream().allMatch(check -> check.state() == WslCheckState.UNKNOWN), result.toString());
        assertTrue(result.getFirst().action().contains("configuration"), result.getFirst().action());
    }
}
