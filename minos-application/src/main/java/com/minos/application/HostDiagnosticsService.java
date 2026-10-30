package com.minos.application;

import com.minos.io.PrivateLocalStorage;
import com.minos.runtime.HostCommandLocator;
import com.minos.runtime.WorkerSandboxProbe;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The host diagnostics a surface reports ({@code minos doctor}): which worker sandboxes the host offers, where the
 * host commands are, and which privacy each location of MINOS_HOME actually has. The answers are application and
 * JDK types; the engine ports behind them stay here (ADR 0058, AUD-ARC-01).
 *
 * <p>Nothing here opens the application or writes to MINOS_HOME: the probe and the locator come from the
 * composition root, and the privacy of a location is read, never repaired.</p>
 */
public final class HostDiagnosticsService {

    /** The locations of MINOS_HOME that hold user code and its derivatives, in the order they are reported. */
    private static final List<String> PRIVATE_LOCATIONS = List.of(
            "", "snapshots", "projects", "runs", "semantic-vectors", "runtime-observations",
            "remote-cache", "remote-cache/repositories", "distributed-artifacts", "postgresql-snapshot-scratch");

    /** Reads the privacy of one location; a seam so that an unreadable location can be tested. */
    @FunctionalInterface
    interface PrivacyReader {
        PrivateLocalStorage.Privacy privacyOf(Path location) throws IOException;
    }

    private final WorkerSandboxProbe sandboxProbe;
    private final HostCommandLocator commandLocator;
    private final PrivacyReader privacyReader;

    /** A service over the given probe and locator; production goes through {@link #defaults()}. */
    HostDiagnosticsService(WorkerSandboxProbe sandboxProbe, HostCommandLocator commandLocator) {
        this(sandboxProbe, commandLocator, PrivateLocalStorage::privacyOf);
    }

    HostDiagnosticsService(WorkerSandboxProbe sandboxProbe, HostCommandLocator commandLocator,
                           PrivacyReader privacyReader) {
        this.sandboxProbe = Objects.requireNonNull(sandboxProbe, "sandboxProbe");
        this.commandLocator = Objects.requireNonNull(commandLocator, "commandLocator");
        this.privacyReader = Objects.requireNonNull(privacyReader, "privacyReader");
    }

    /** The real host: the probe and the locator of the composition root (ADR 0042), resolved at this call. */
    public static HostDiagnosticsService defaults() {
        MinosApplicationComposer root = MinosApplicationComposers.resolve();
        return new HostDiagnosticsService(root.workerSandboxProbe(), root.hostCommandLocator());
    }

    /** The worker sandboxes of this host: managed local providers first, then untrusted code. */
    public WorkerSandboxDiagnosis workerSandbox(Path home) {
        WorkerSandboxProbe.ManagedLocalSandbox managedLocal = sandboxProbe.managedLocalProvider(home);
        WorkerSandboxProbe.UntrustedCodeSandbox untrusted = sandboxProbe.untrustedCode(home);
        return new WorkerSandboxDiagnosis(
                managedLocal.backendId(),
                managedLocal.available(),
                untrusted.backendId(),
                untrusted.supportsUntrustedCode(),
                untrusted.cause(),
                untrusted.rejectedBackendId(),
                untrusted.rejectionReasons(),
                untrusted.refusalReport());
    }

    /** The executable the host resolves for {@code command}, absent if there is none. */
    public Optional<Path> locateCommand(String command) {
        return commandLocator.find(command);
    }

    /**
     * The privacy actually enforced on each location of {@code home} that holds user code or its derivatives,
     * keyed by its label ({@code MINOS_HOME} for the root, else the path relative to it), in a fixed order: one of
     * {@code ENFORCED}, {@code EXPOSED}, {@code ABSENT}, {@code UNSUPPORTED}, or {@code UNKNOWN} when it cannot be
     * read. Only the level is reported, never any content.
     */
    public Map<String, String> privateStorage(Path home) {
        Map<String, String> report = new LinkedHashMap<>();
        for (String relative : PRIVATE_LOCATIONS) {
            Path target = relative.isEmpty() ? home : home.resolve(relative);
            String label = relative.isEmpty() ? "MINOS_HOME" : relative;
            try {
                report.put(label, privacyReader.privacyOf(target).name());
            } catch (IOException _) {
                report.put(label, "UNKNOWN");
            }
        }
        return report;
    }
}
