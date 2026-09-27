package com.minos.cli;

import com.minos.io.PrivateLocalStorage;
import com.minos.output.SymbolOutputFormat;
import com.minos.application.MinosApplicationComposers;
import com.minos.runtime.WorkerSandboxProbe;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Installation and provider-runtime diagnostics.
 *
 * <p>The {@code workerSandbox} section says which sandbox backend managed local providers run
 * under, whether remote indexing of untrusted code is available on this host and, when it is not,
 * exactly why — distinguishing a <em>missing operator prerequisite</em> (no OS backend could be
 * discovered: which one) from the <em>ADR 0041 decision</em> (an OS backend exists but its write
 * quota is supervised, not OS-enforced: which dimensions). Only the latter carries the
 * {@code decision} marker. Neither turns the verdict into {@code ACTION_REQUIRED}: the decision is
 * final, and a missing sandbox prerequisite does not block local indexing of managed providers.
 * The executor-capability cause is per provider and belongs to the worker's own refusal, not to
 * this host-level report. The section carries identifiers and codes only, never a path.</p>
 */
public final class DoctorCommand {
    public static final String NAME = "doctor";
    private static final String DECISION = "ADR 0041";

    private final Path home;
    private final AutonomousIndexOperations operations;
    private final Function<Path, WorkerSandboxReport> sandboxProbe;
    private final Function<String, Optional<Path>> commandLocator;

    /**
     * Production wiring: the sandbox probe and the command lookup are the real host ones supplied by
     * the composition root (ADR 0042) — there is no production path that injects another probe.
     */
    public DoctorCommand(Path home, AutonomousIndexOperations operations) {
        this(home, operations, DoctorCommand::probeWorkerSandbox);
    }

    /** Package-private seam: the sandbox probe is injectable so the report can be tested without an OS probe. */
    DoctorCommand(Path home, AutonomousIndexOperations operations, Function<Path, WorkerSandboxReport> sandboxProbe) {
        this.home = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
        this.operations = Objects.requireNonNull(operations, "operations");
        this.sandboxProbe = Objects.requireNonNull(sandboxProbe, "sandboxProbe");
        this.commandLocator = DoctorCommand::locateCommand;
    }

    /**
     * What {@code doctor} reports about worker sandboxes. Backend identifiers and machine-readable
     * reason codes only: no filesystem path, user name or cgroup name ever enters this record.
     *
     * @param managedLocalBackend   backend selected for managed local providers
     * @param managedLocalAvailable whether that backend honours the managed-local contract here
     * @param untrustedCodeBackend  backend the strict (remote) selector ends on
     * @param remoteIndexingAvailable whether untrusted remote execution is qualified here
     * @param cause                 selection cause name ({@link WorkerSandboxProbe.UntrustedCodeSandbox#cause()}): why it is (un)available
     * @param rejectedBackend       OS backend discovered but rejected for untrusted code, if any
     * @param reasons               exact unmet dimension codes (decision) or missing prerequisite codes
     * @param reason                single-line, path-free explanation of the refusal ("" when available)
     */
    record WorkerSandboxReport(
            String managedLocalBackend,
            boolean managedLocalAvailable,
            String untrustedCodeBackend,
            boolean remoteIndexingAvailable,
            String cause,
            Optional<String> rejectedBackend,
            List<String> reasons,
            String reason
    ) {
        WorkerSandboxReport {
            Objects.requireNonNull(managedLocalBackend, "managedLocalBackend");
            Objects.requireNonNull(untrustedCodeBackend, "untrustedCodeBackend");
            Objects.requireNonNull(cause, "cause");
            Objects.requireNonNull(rejectedBackend, "rejectedBackend");
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
            reason = reason == null ? "" : reason;
        }

        boolean closedByDecision() {
            return WorkerSandboxProbe.CAUSE_REJECTED_BY_DECISION.equals(cause);
        }
    }

    /** Real host probe (managed-local first, then untrusted code), through the composition root. */
    static WorkerSandboxReport probeWorkerSandbox(Path home) {
        WorkerSandboxProbe probe = MinosApplicationComposers.resolve().workerSandboxProbe();
        WorkerSandboxProbe.ManagedLocalSandbox managedLocal = probe.managedLocalProvider(home);
        WorkerSandboxProbe.UntrustedCodeSandbox untrusted = probe.untrustedCode(home);
        return new WorkerSandboxReport(
                managedLocal.backendId(),
                managedLocal.available(),
                untrusted.backendId(),
                untrusted.supportsUntrustedCode(),
                untrusted.cause(),
                untrusted.rejectedBackendId(),
                untrusted.rejectionReasons(),
                untrusted.refusalReport());
    }

    private static Optional<Path> locateCommand(String command) {
        return MinosApplicationComposers.resolve().hostCommandLocator().find(command);
    }

    public int run(String[] arguments, Appendable output, Appendable error) throws IOException {
        SymbolOutputFormat format;
        try {
            format = parse(arguments);
        } catch (IllegalArgumentException exception) {
            error.append("error: ").append(exception.getMessage()).append('\n')
                    .append("Usage: minos doctor [--format <text|json>]\n");
            return FindSymbolCommand.USAGE_ERROR;
        }
        List<AutonomousIndexOperations.ProviderView> providers = operations.providers();
        Map<String, String> commands = new LinkedHashMap<>();
        for (String command : List.of("java", "javac", "mvn", "node", "npm", "python", "docker")) {
            commands.put(command, commandLocator.apply(command).map(Path::toString).orElse(null));
        }
        Map<String, String> privateStorage = privateStorageDiagnostics();
        WorkerSandboxReport sandbox = Objects.requireNonNull(sandboxProbe.apply(home), "worker sandbox report");
        // Remote indexing being closed by decision (ADR 0041) is a fact to expose, not an action to require.
        boolean ready = providers.stream()
                .filter(AutonomousIndexOperations.ProviderView::requiredByDefault)
                .allMatch(provider -> "READY".equals(provider.state()));
        if (format == SymbolOutputFormat.JSON) {
            renderJson(output, providers, commands, privateStorage, sandbox, ready);
        } else {
            renderText(output, providers, commands, privateStorage, sandbox, ready);
        }
        return ready ? FindSymbolCommand.SUCCESS : FindSymbolCommand.EXECUTION_ERROR;
    }

    private void renderJson(
            Appendable output,
            List<AutonomousIndexOperations.ProviderView> providers,
            Map<String, String> commands,
            Map<String, String> privateStorage,
            WorkerSandboxReport sandbox,
            boolean ready
    ) throws IOException {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("minosHome", home.toString());
        map.put("javaRuntime", System.getProperty("java.runtime.version"));
        map.put("javaHome", System.getProperty("java.home"));
        map.put("commands", commands);
        List<Map<String, Object>> values = new ArrayList<>();
        for (var provider : providers) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", provider.id());
            value.put("version", provider.version());
            value.put("state", provider.state());
            value.put("requiredByDefault", provider.requiredByDefault());
            value.put("executable", provider.executable());
            value.put("diagnostics", CliCommandSupport.publicDiagnostics(provider.diagnostics()));
            values.add(value);
        }
        map.put("providers", values);
        map.put("privateStoragePermissions", privateStorage);
        map.put("workerSandbox", sandboxJson(sandbox));
        map.put("ready", ready);
        output.append(CliJson.render(map)).append('\n');
    }

    private static Map<String, Object> sandboxJson(WorkerSandboxReport sandbox) {
        Map<String, Object> managedLocal = new LinkedHashMap<>();
        managedLocal.put("backend", sandbox.managedLocalBackend());
        managedLocal.put("available", sandbox.managedLocalAvailable());
        Map<String, Object> untrusted = new LinkedHashMap<>();
        untrusted.put("backend", sandbox.untrustedCodeBackend());
        untrusted.put("available", sandbox.remoteIndexingAvailable());
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("managedLocalProvider", managedLocal);
        map.put("untrustedCode", untrusted);
        map.put("remoteIndexing", availability(sandbox.remoteIndexingAvailable()));
        map.put("cause", sandbox.cause());
        map.put("rejectedBackend", sandbox.rejectedBackend().orElse(null));
        map.put("reasons", sandbox.reasons());
        map.put("reason", sandbox.reason());
        // Only a rejection by decision is governed by the ADR; a missing prerequisite is an operator action.
        map.put("decision", sandbox.closedByDecision() ? DECISION : null);
        return map;
    }

    private static String availability(boolean available) {
        return available ? "AVAILABLE" : "UNAVAILABLE";
    }

    private void renderText(
            Appendable output,
            List<AutonomousIndexOperations.ProviderView> providers,
            Map<String, String> commands,
            Map<String, String> privateStorage,
            WorkerSandboxReport sandbox,
            boolean ready
    ) throws IOException {
        output.append("MINOS_HOME: ").append(home.toString()).append('\n');
        output.append("Java runtime: ").append(System.getProperty("java.runtime.version", "unknown")).append('\n');
        output.append("Java home: ").append(System.getProperty("java.home", "unknown")).append('\n');
        for (Map.Entry<String, String> entry : commands.entrySet()) {
            output.append("command[").append(entry.getKey()).append("]: ")
                    .append(entry.getValue() == null ? "NOT_FOUND" : entry.getValue()).append('\n');
        }
        output.append(ToolsCommand.render(providers, SymbolOutputFormat.TEXT)).append('\n');
        for (Map.Entry<String, String> entry : privateStorage.entrySet()) {
            output.append("privateStorage[").append(entry.getKey()).append("]: ")
                    .append(entry.getValue()).append('\n');
        }
        output.append("workerSandbox[managedLocalProvider]: ").append(sandbox.managedLocalBackend())
                .append(' ').append(availability(sandbox.managedLocalAvailable())).append('\n');
        output.append("workerSandbox[untrustedCode]: ").append(sandbox.untrustedCodeBackend())
                .append(' ').append(availability(sandbox.remoteIndexingAvailable())).append('\n');
        output.append("workerSandbox[remoteIndexing]: ").append(availability(sandbox.remoteIndexingAvailable()))
                .append(sandbox.closedByDecision() ? " (decision: " + DECISION + ")" : "").append('\n');
        if (!sandbox.remoteIndexingAvailable()) {
            output.append("workerSandbox[cause]: ").append(sandbox.cause()).append('\n');
            output.append("workerSandbox[reason]: ").append(sandbox.reason()).append('\n');
        }
        output.append("verdict: ").append(ready ? "READY" : "ACTION_REQUIRED").append('\n');
    }

    /**
     * Reports the confidentiality actually enforced on the locations that hold user code and its
     * derivatives. Only the location and its enforcement level are reported -- never any content.
     *
     * <p>{@code EXPOSED} means the directory grants access beyond its owner, which MINOS repairs the
     * next time it opens that store; {@code UNSUPPORTED} means the filesystem has no permission
     * model to enforce, which is surfaced rather than silently treated as private.</p>
     */
    private Map<String, String> privateStorageDiagnostics() {
        Map<String, String> report = new LinkedHashMap<>();
        for (String relative : List.of(
                "", "snapshots", "projects", "runs", "semantic-vectors", "runtime-observations",
                "remote-cache", "remote-cache/repositories", "distributed-artifacts",
                "postgresql-snapshot-scratch")) {
            Path target = relative.isEmpty() ? home : home.resolve(relative);
            String label = relative.isEmpty() ? "MINOS_HOME" : relative;
            try {
                report.put(label, PrivateLocalStorage.privacyOf(target).name());
            } catch (IOException unreadable) {
                report.put(label, "UNKNOWN");
            }
        }
        return report;
    }

    private static SymbolOutputFormat parse(String[] arguments) {
        if (arguments.length == 0) return SymbolOutputFormat.TEXT;
        if (arguments.length == 1 && ("--help".equals(arguments[0]) || "-h".equals(arguments[0]))) return SymbolOutputFormat.TEXT;
        if (arguments.length == 2 && "--format".equals(arguments[0])) return SymbolOutputFormat.parse(arguments[1]);
        throw new IllegalArgumentException("unexpected doctor arguments");
    }
}
