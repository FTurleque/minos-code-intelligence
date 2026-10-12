package com.minos.runtime.local.sandbox;

import com.minos.runtime.local.process.CommandLocator;
import com.minos.runtime.local.process.IndexerProcessPlan;
import com.minos.runtime.local.process.ProcessIndexerExecutor;
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
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The plan the Job Object ownership launcher receives, and what is refused before any plan exists.
 * Nothing is started: the transformer only resolves the provider command, filters its environment and
 * writes the plan the launcher reads.
 */
@EnabledOnOs(OS.WINDOWS)
class WindowsJobObjectProcessOwnershipPlanTest {

    private static final String PLAN_FILE = "windows-job-object-plan.txt";
    private static final String LAUNCHER = "windows-job-object-owner-v1.ps1";

    @TempDir
    Path temporary;

    @Test
    void thePlanCarriesTheResolvedCommandTheDeclaredEnvironmentAndNoSecret() throws Exception {
        Path working = Files.createDirectory(temporary.resolve("working"));
        Path run = Files.createDirectory(temporary.resolve("run"));
        Path artifact = run.resolve("index.scip");
        Path provider = Files.writeString(temporary.resolve("provider.exe"), "never executed");
        IndexerProcessPlan original = new IndexerProcessPlan(
                List.of(provider.toString(), "index", "an argument with spaces"),
                working,
                Map.of("PROVIDER_TEST_EXPLICIT", "allowed", "MINOS_TEAM_TOKEN", "s3cr3t-value-never-forwarded"),
                artifact,
                Duration.ofSeconds(45));
        ProcessIndexerExecutor.ProcessPlanTransformer transformer = ownership().transformer();

        IndexerProcessPlan transformed = transformer.transform(original, run);

        Map<String, String> plan = readPlan(run);
        assertEquals("3", plan.get("command.count"));
        assertEquals(provider.toAbsolutePath().normalize().toString(), decode(plan.get("command.0")));
        assertEquals("index", decode(plan.get("command.1")));
        assertEquals("an argument with spaces", decode(plan.get("command.2")));
        assertEquals(working.toAbsolutePath().normalize().toString(), decode(plan.get("working")));
        Map<String, String> environment = environmentOf(plan);
        assertEquals("allowed", environment.get("PROVIDER_TEST_EXPLICIT"));
        assertNull(environment.get("MINOS_TEAM_TOKEN"), "a secret-looking variable is never forwarded");
        assertFalse(environment.containsValue("s3cr3t-value-never-forwarded"));
        assertEquals(environment.keySet().stream()
                        .sorted(Comparator.comparing(key -> key.toLowerCase(Locale.ROOT))).toList(),
                List.copyOf(environment.keySet()), "the environment is written in a stable order");

        assertTrue(transformer.isTrustedLauncher());
        assertEquals(Map.of(), transformed.environment(), "the launcher gets no provider environment");
        assertEquals(original.workingDirectory(), transformed.workingDirectory());
        assertEquals(artifact.toAbsolutePath().normalize(), transformed.generatedArtifact());
        assertEquals(Duration.ofSeconds(45), transformed.timeout());
        List<String> command = transformed.command();
        assertEquals(CommandLocator.windowsPowerShell().orElseThrow().toString(), command.get(0));
        assertEquals(run.resolve(PLAN_FILE).toAbsolutePath().normalize().toString(),
                command.get(command.indexOf("-Plan") + 1));
        assertEquals(LAUNCHER, Path.of(command.get(command.indexOf("-File") + 1)).getFileName().toString());
    }

    @Test
    void aMissingProviderExecutableIsRefusedAndNoPlanIsWritten() throws Exception {
        Path run = Files.createDirectory(temporary.resolve("run"));
        IndexerProcessPlan original = plan(List.of(temporary.resolve("missing-provider.exe").toString()), Map.of());
        ProcessIndexerExecutor.ProcessPlanTransformer transformer = ownership().transformer();

        IOException refusal = assertThrows(IOException.class, () -> transformer.transform(original, run));

        assertTrue(refusal.getMessage().startsWith("provider executable is missing"), refusal.getMessage());
        assertFalse(Files.exists(run.resolve(PLAN_FILE)), "a refused plan is never written");
    }

    @Test
    void aProviderNameThatResolvesToNothingIsRefusedAndNoPlanIsWritten() throws Exception {
        Path run = Files.createDirectory(temporary.resolve("run"));
        String unknown = "minos-no-such-provider-" + UUID.randomUUID();
        IndexerProcessPlan original = plan(List.of(unknown, "index"), Map.of());
        ProcessIndexerExecutor.ProcessPlanTransformer transformer = ownership().transformer();

        IOException refusal = assertThrows(IOException.class, () -> transformer.transform(original, run));

        assertEquals("provider executable is not resolvable: " + unknown, refusal.getMessage());
        assertFalse(Files.exists(run.resolve(PLAN_FILE)), "a refused plan is never written");
    }

    @Test
    void aNulInAProviderArgumentIsRefusedAndNoPlanIsWritten() throws Exception {
        Path run = Files.createDirectory(temporary.resolve("run"));
        Path provider = Files.writeString(temporary.resolve("provider.exe"), "never executed");
        IndexerProcessPlan original = plan(List.of(provider.toString(), "before\0after"), Map.of());
        ProcessIndexerExecutor.ProcessPlanTransformer transformer = ownership().transformer();

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> transformer.transform(original, run));

        assertEquals("Windows Job Object plan value contains a forbidden NUL", refusal.getMessage());
        assertFalse(Files.exists(run.resolve(PLAN_FILE)), "a refused plan is never written");
    }

    @Test
    void aNulInADeclaredEnvironmentValueIsRefusedAndNoPlanIsWritten() throws Exception {
        Path run = Files.createDirectory(temporary.resolve("run"));
        Path provider = Files.writeString(temporary.resolve("provider.exe"), "never executed");
        IndexerProcessPlan original = plan(List.of(provider.toString()), Map.of("PROVIDER_TEST_EXPLICIT", "a\0b"));
        ProcessIndexerExecutor.ProcessPlanTransformer transformer = ownership().transformer();

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> transformer.transform(original, run));

        assertEquals("Windows Job Object plan value contains a forbidden NUL", refusal.getMessage());
        assertFalse(Files.exists(run.resolve(PLAN_FILE)), "a refused plan is never written");
    }

    @Test
    void aNullPlanIsRefused() throws Exception {
        Path run = Files.createDirectory(temporary.resolve("run"));
        ProcessIndexerExecutor.ProcessPlanTransformer transformer = ownership().transformer();

        NullPointerException refusal = assertThrows(NullPointerException.class,
                () -> transformer.transform(null, run));

        assertEquals("plan", refusal.getMessage());
        assertFalse(Files.exists(run.resolve(PLAN_FILE)), "a refused plan is never written");
    }

    @Test
    void ownershipWhoseLauncherWouldSitInsideMinosHomeIsNotDiscoveredAndTheRefusalIsLogged() throws Exception {
        // The launcher root itself is given as MINOS_HOME: the script MINOS would run then sits among the data.
        Path launcherRoot = SandboxLauncherScript.materialize(LAUNCHER).file().getParent().getParent();

        try (LogCapture logs = new LogCapture(WindowsJobObjectProcessOwnership.class)) {
            Optional<WindowsJobObjectProcessOwnership> discovered =
                    WindowsJobObjectProcessOwnership.discover(launcherRoot);

            assertTrue(discovered.isEmpty(), "no ownership is offered when its launcher cannot be trusted");
            assertEquals(List.of("MINOS Windows Job Object process ownership is unavailable"), logs.warnings());
        }
        assertFalse(Files.exists(launcherRoot.resolve("sandbox")), "nothing is created in the refused home");
    }

    // ------------------------------------------------------------------------------ helpers

    private WindowsJobObjectProcessOwnership ownership() throws IOException {
        Path home = Files.createDirectories(temporary.resolve("home"));
        return WindowsJobObjectProcessOwnership.discover(home)
                .orElseThrow(() -> new AssertionError("Job Object ownership needs only Windows PowerShell"));
    }

    private IndexerProcessPlan plan(List<String> command, Map<String, String> environment) throws IOException {
        Path working = Files.createDirectories(temporary.resolve("working"));
        return new IndexerProcessPlan(
                command, working, environment, temporary.resolve("run").resolve("index.scip"), Duration.ofSeconds(30));
    }

    private static Map<String, String> readPlan(Path run) throws IOException {
        Map<String, String> plan = new LinkedHashMap<>();
        for (String line : Files.readAllLines(run.resolve(PLAN_FILE), StandardCharsets.UTF_8)) {
            int separator = line.indexOf('=');
            if (separator > 0) plan.put(line.substring(0, separator), line.substring(separator + 1));
        }
        return plan;
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
}
