package com.minos.bootstrap;

import com.minos.application.WslIndexingRoute;
import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.runtime.ProviderRuntimeManager;
import com.minos.runtime.ProviderRuntimeStatus;
import com.minos.runtime.local.wsl.WslCommandResult;
import com.minos.runtime.local.wsl.WslCommandRunner;
import com.minos.runtime.local.wsl.WslIndexerExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The WSL2 route of the composition root: Windows only, scip-java routed by the probe (ADR SEC-0079). */
class LocalWslIndexingRouteTest {

    private static final String LISTING = "  NAME      STATE           VERSION\r\n* Ubuntu    Running         2\r\n";
    private static final String HEALTHY = "systemd=yes\nuserbus=yes\ncgroup2=yes\ncontrollers=cpuset cpu io memory pids\n"
            + "bwrap=yes\njvm=java.specification.version = 21\nend=ok\n";

    @TempDir Path temp;

    @Test
    void withoutWslScipJavaIsBlockedByItsDoctorIdentifierAndHasNoExecutor() throws Exception {
        List<List<String>> commands = new ArrayList<>();
        ProviderRuntimeManager runtimes = route(Optional.empty(), recording(commands), jar())
                .runtimes(new NativeRuntimes(), Optional.empty());

        ProviderRuntimeStatus status = runtimes.inspect("scip-java");

        assertEquals(ProviderRuntimeStatus.State.BLOCKED, status.state());
        assertTrue(status.diagnostics().getFirst().startsWith("WSL route prerequisite wsl-version is missing"),
                status.diagnostics().toString());
        assertThrows(IllegalStateException.class, () -> runtimes.executor("scip-java"));
        assertTrue(commands.isEmpty(), "nothing is launched without wsl.exe");
    }

    @Test
    void outsideAPackagedInstallationTheRouteRefusesBeforeProbing() {
        List<List<String>> commands = new ArrayList<>();
        ProviderRuntimeManager runtimes = route(Optional.of(temp.resolve("wsl.exe")), recording(commands),
                Optional.empty()).runtimes(new NativeRuntimes(), Optional.of("Ubuntu"));

        assertEquals(List.of(LocalWslIndexingRoute.NOT_PACKAGED), runtimes.inspect("scip-java").diagnostics());
        assertTrue(commands.isEmpty());
    }

    @Test
    void aDistributionWithoutBubblewrapBlocksScipJavaWithoutAnyPath() throws Exception {
        WslCommandRunner runner = (command, timeout) -> command.contains("-l")
                ? new WslCommandResult(0, LISTING.getBytes(StandardCharsets.UTF_16LE))
                : new WslCommandResult(0, HEALTHY.replace("bwrap=yes", "bwrap=no").getBytes(StandardCharsets.UTF_8));
        ProviderRuntimeManager runtimes = route(Optional.of(temp.resolve("wsl.exe")), runner, jar())
                .runtimes(new NativeRuntimes(), Optional.empty());

        String diagnostic = runtimes.inspect("scip-java").diagnostics().getFirst();

        assertTrue(diagnostic.startsWith("WSL route prerequisite bubblewrap is missing"), diagnostic);
        assertFalse(diagnostic.contains(temp.toString()), diagnostic);
    }

    @Test
    void everyOtherProviderStaysNative() throws Exception {
        NativeRuntimes natives = new NativeRuntimes();
        ProviderRuntimeManager runtimes = route(Optional.empty(), recording(new ArrayList<>()), jar())
                .runtimes(natives, Optional.empty());

        assertSame(natives.typeScript, runtimes.inspect("scip-typescript"));
        assertSame(natives.executor, runtimes.executor("scip-typescript"));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aReadyDistributionYieldsTheVerifiedWslExecutorOnWindows() throws Exception {
        WslCommandRunner runner = (command, timeout) -> command.contains("-l")
                ? new WslCommandResult(0, LISTING.getBytes(StandardCharsets.UTF_16LE))
                : new WslCommandResult(0, HEALTHY.getBytes(StandardCharsets.UTF_8));
        ProviderRuntimeManager runtimes = route(Optional.of(temp.resolve("wsl.exe")), runner, jar())
                .runtimes(new NativeRuntimes(), Optional.empty());

        assertEquals(ProviderRuntimeStatus.State.READY, runtimes.inspect("scip-java").state());
        IndexerExecutor executor = runtimes.executor("scip-java");

        assertInstanceOf(WslIndexerExecutor.class, executor);
        assertEquals("scip-java", executor.indexerId());
    }

    @Test
    void theRouteIsOfferedOnWindowsOnlyAndTheWorkerOnLinuxOnly() {
        DefaultMinosApplicationComposer composer = new DefaultMinosApplicationComposer();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

        assertEquals(os.startsWith("windows"), composer.wslIndexingRoute(temp).isPresent());
        assertEquals(os.startsWith("linux"), composer.wslIndexingWorker(temp).isPresent());
    }

    @Test
    void onlyARegularJarIsTheInstallationJar() throws Exception {
        Path jar = jar().orElseThrow();
        Path classes = Files.createDirectories(temp.resolve("classes"));

        assertEquals(Optional.of(jar), LocalWslIndexingRoute.installationJar(source(jar)));
        assertEquals(Optional.empty(), LocalWslIndexingRoute.installationJar(source(classes)));
        assertEquals(Optional.empty(), LocalWslIndexingRoute.installationJar(null));
    }

    private WslIndexingRoute route(Optional<Path> wsl, WslCommandRunner runner, Optional<Path> jar) {
        return new LocalWslIndexingRoute(temp.resolve("home"), wsl, runner, jar);
    }

    private Optional<Path> jar() throws Exception {
        Path jar = temp.resolve("minos.jar");
        if (!Files.exists(jar)) {
            Files.writeString(jar, "");
        }
        return Optional.of(jar);
    }

    private static CodeSource source(Path path) throws Exception {
        URL url = path.toUri().toURL();
        return new CodeSource(url, (Certificate[]) null);
    }

    private static WslCommandRunner recording(List<List<String>> commands) {
        return (command, timeout) -> {
            commands.add(command);
            throw new java.io.IOException("unexpected command");
        };
    }

    /** Native runtimes in which scip-java is blocked, as on Windows, and scip-typescript is ready. */
    private static final class NativeRuntimes implements ProviderRuntimeManager {
        private final ProviderRuntimeStatus typeScript = new ProviderRuntimeStatus("scip-typescript", "0.3.0",
                ProviderRuntimeStatus.State.READY, Optional.empty(), List.of(), true);
        private final IndexerExecutor executor = new IndexerExecutor() {
            @Override public String indexerId() { return "scip-typescript"; }
            @Override public com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact execute(
                    com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest request) {
                throw new UnsupportedOperationException();
            }
        };

        @Override public List<ProviderRuntimeStatus> list() { return List.of(typeScript); }

        @Override
        public ProviderRuntimeStatus inspect(String providerId) {
            if ("scip-java".equals(providerId)) {
                throw new AssertionError("the native status of a routed provider is never consulted");
            }
            return typeScript;
        }

        @Override public ProviderRuntimeStatus install(String providerId) { return typeScript; }

        @Override
        public IndexerExecutor executor(String providerId) {
            if ("scip-java".equals(providerId)) {
                throw new AssertionError("the native executor of a routed provider is never resolved");
            }
            return executor;
        }
    }
}
