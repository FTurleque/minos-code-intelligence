package com.minos.cli;

import com.minos.output.SymbolOutputFormat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Commande d'indexation autonome M14 avec compatibilité d'import M9. */
public final class IndexCommand {

    public static final String NAME = "index";
    private static final Set<String> SUPPORTED_OPTIONS = Set.of(
            "--provider", "--force-full", "--dry-run", "--format",
            "--scip", "--provider-version", "--module", "--snapshot"
    );
    private static final String USAGE = """
            Usage: minos index <project> [options]

            Autonomous indexing:
              --provider <id>               Override provider negotiation
              --force-full                  Force a complete provider execution
              --dry-run                     Show discovery/runtime/plan without executing
              --format <text|json>          Output format (default: text)

            Deprecated compatibility import:
              --scip <index.scip>           Import an already generated SCIP artifact
              --provider <id>               Required with --scip
              --provider-version <version>  Provider version when known
              --module <module>             Module identifier associated with the import
              --snapshot <id>               Explicit snapshot id

            Prefer `minos import-scip` for manual artifacts.
            """.stripTrailing();

    private final ProjectOperations projectOperations;
    private final AutonomousIndexOperations autonomousOperations;

    public IndexCommand(ProjectOperations projectOperations) {
        this(projectOperations, null);
    }

    public IndexCommand(ProjectOperations projectOperations, AutonomousIndexOperations autonomousOperations) {
        this.projectOperations = Objects.requireNonNull(projectOperations, "projectOperations");
        this.autonomousOperations = autonomousOperations;
    }

    public int run(String[] arguments, Appendable output, Appendable error) throws IOException {
        return CliCommandSupport.run(arguments, output, error, USAGE, Options::parse, NAME, options -> {
            if (options.scipFile() != null) {
                error.append("warning: `minos index --scip` is deprecated; use `minos import-scip`\n");
                ProjectOperations.IndexImportResult imported = projectOperations.importScip(
                        options.project(), options.scipFile(), options.providerId(),
                        options.providerVersion(), options.moduleId(), options.snapshotId());
                output.append(renderImport(imported, options.format())).append('\n');
                if (imported.commitStatus() == ProjectOperations.IndexImportCommitStatus.COMMITTED_METADATA_PENDING) {
                    error.append("warning: snapshot committed but project metadata recovery is pending")
                            .append(imported.diagnostic() == null ? "" : ": " + imported.diagnostic())
                            .append('\n');
                }
                return FindSymbolCommand.SUCCESS;
            }
            if (autonomousOperations == null) {
                throw new IllegalStateException("autonomous indexing is not configured in this CLI bootstrap");
            }
            if (options.dryRun()) {
                output.append(renderPlan(autonomousOperations.plan(
                        options.project(), options.providerId(), options.forceFull()), options.format())).append('\n');
            } else {
                output.append(renderExecution(autonomousOperations.execute(
                        options.project(), options.providerId(), options.forceFull()), options.format())).append('\n');
            }
            return FindSymbolCommand.SUCCESS;
        });
    }

    public static String usage() {
        return USAGE;
    }

    static String renderPlan(AutonomousIndexOperations.IndexPlanView plan, SymbolOutputFormat format) {
        Map<String, Object> map = planMap(plan);
        if (format == SymbolOutputFormat.JSON) {
            return CliJson.render(map);
        }
        List<String> lines = new ArrayList<>();
        lines.add("projectId: " + plan.projectId());
        lines.add("project: " + plan.projectName());
        lines.add("root: " + plan.rootPath());
        lines.add("languages: " + String.join(",", plan.languages()));
        lines.add("buildSystems: " + String.join(",", plan.buildSystems()));
        lines.add("providers: " + String.join(",", plan.providerIds()));
        lines.add("mode: " + plan.mode());
        lines.add("reasons: " + String.join(",", plan.reasons()));
        lines.add("changedFiles: " + plan.changedFiles().size());
        for (AutonomousIndexOperations.ProviderView runtime : plan.providerRuntimes()) {
            lines.add("runtime[" + runtime.id() + "]: " + runtime.state()
                    + (runtime.diagnostics().isEmpty() ? "" : " — " + String.join("; ", runtime.diagnostics())));
        }
        return String.join("\n", lines);
    }

    private static String renderExecution(
            AutonomousIndexOperations.IndexExecutionView execution,
            SymbolOutputFormat format
    ) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("plan", planMap(execution.plan()));
        map.put("runId", execution.runId());
        map.put("status", execution.status());
        map.put("activeSnapshotId", execution.activeSnapshotId());
        map.put("fingerprintPromoted", execution.fingerprintPromoted());
        map.put("diagnostic", execution.diagnostic());
        if (format == SymbolOutputFormat.JSON) {
            return CliJson.render(map);
        }
        return renderPlan(execution.plan(), SymbolOutputFormat.TEXT) + "\n"
                + "runId: " + nullable(execution.runId()) + "\n"
                + "status: " + execution.status() + "\n"
                + "activeSnapshotId: " + nullable(execution.activeSnapshotId()) + "\n"
                + "fingerprintPromoted: " + execution.fingerprintPromoted() + "\n"
                + "diagnostic: " + nullable(execution.diagnostic());
    }

    private static Map<String, Object> planMap(AutonomousIndexOperations.IndexPlanView plan) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("projectId", plan.projectId());
        map.put("project", plan.projectName());
        map.put("root", plan.rootPath());
        map.put("languages", plan.languages());
        map.put("buildSystems", plan.buildSystems());
        map.put("providers", plan.providerIds());
        map.put("mode", plan.mode().name());
        map.put("reasons", plan.reasons());
        map.put("changedFiles", plan.changedFiles());
        map.put("forcedFull", plan.forcedFull());
        List<Map<String, Object>> runtimes = new ArrayList<>();
        for (AutonomousIndexOperations.ProviderView runtime : plan.providerRuntimes()) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", runtime.id());
            value.put("version", runtime.version());
            value.put("state", runtime.state());
            value.put("executable", runtime.executable());
            value.put("diagnostics", runtime.diagnostics());
            runtimes.add(value);
        }
        map.put("providerRuntimes", runtimes);
        return map;
    }

    private static String renderImport(ProjectOperations.IndexImportResult result, SymbolOutputFormat format) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("projectId", result.projectId());
        map.put("snapshotId", result.snapshotId());
        map.put("providerId", result.providerId());
        map.put("providerVersion", result.providerVersion());
        map.put("normalizedSymbolCount", result.normalizedSymbolCount());
        map.put("occurrenceCount", result.occurrenceCount());
        map.put("relationshipCount", result.relationshipCount());
        map.put("relatedTestRelationshipCount", result.relatedTestRelationshipCount());
        map.put("unresolvedOccurrenceCount", result.unresolvedOccurrenceCount());
        map.put("unresolvedRelationshipCount", result.unresolvedRelationshipCount());
        map.put("completedAt", result.completedAt());
        map.put("commitStatus", result.commitStatus().name());
        map.put("diagnostic", result.diagnostic());
        if (format == SymbolOutputFormat.JSON) {
            return CliJson.render(map);
        }
        return String.join("\n",
                "projectId: " + result.projectId(),
                "snapshotId: " + result.snapshotId(),
                "providerId: " + result.providerId(),
                "providerVersion: " + nullable(result.providerVersion()),
                "normalizedSymbols: " + result.normalizedSymbolCount(),
                "occurrences: " + result.occurrenceCount(),
                "relationships: " + result.relationshipCount(),
                "relatedTests: " + result.relatedTestRelationshipCount(),
                "unresolvedOccurrences: " + result.unresolvedOccurrenceCount(),
                "unresolvedRelationships: " + result.unresolvedRelationshipCount(),
                "completedAt: " + result.completedAt(),
                "commitStatus: " + result.commitStatus().name(),
                "diagnostic: " + nullable(result.diagnostic()));
    }

    private static String nullable(String value) {
        return value == null ? "-" : value;
    }

    private record Options(
            String project,
            String providerId,
            boolean forceFull,
            boolean dryRun,
            SymbolOutputFormat format,
            Path scipFile,
            String providerVersion,
            String moduleId,
            String snapshotId
    ) {
        private static Options parse(String[] arguments) {
            if (arguments.length < 1) {
                throw new IllegalArgumentException("expected <project>");
            }
            String project = CliCommandSupport.operand(arguments[0], "project");
            String provider = null;
            boolean forceFull = false;
            boolean dryRun = false;
            SymbolOutputFormat format = SymbolOutputFormat.TEXT;
            Path scip = null;
            String providerVersion = null;
            String module = null;
            String snapshot = null;
            Set<String> seen = new HashSet<>();
            for (int index = 1; index < arguments.length; index++) {
                String option = arguments[index];
                if (option == null || !SUPPORTED_OPTIONS.contains(option)) {
                    throw new IllegalArgumentException("unknown option: " + option);
                }
                if (!seen.add(option)) {
                    throw new IllegalArgumentException("duplicate option: " + option);
                }
                if ("--force-full".equals(option)) {
                    forceFull = true;
                    continue;
                }
                if ("--dry-run".equals(option)) {
                    dryRun = true;
                    continue;
                }
                if (++index >= arguments.length || arguments[index] == null || arguments[index].isBlank()
                        || arguments[index].startsWith("--")) {
                    throw new IllegalArgumentException("missing value for " + option);
                }
                String value = arguments[index];
                switch (option) {
                    case "--provider" -> provider = value;
                    case "--format" -> format = SymbolOutputFormat.parse(value);
                    case "--scip" -> scip = Path.of(value);
                    case "--provider-version" -> providerVersion = value;
                    case "--module" -> module = value;
                    case "--snapshot" -> snapshot = value;
                    default -> throw new IllegalStateException("unhandled option: " + option);
                }
            }
            if (scip != null) {
                if (provider == null || provider.isBlank()) {
                    throw new IllegalArgumentException("--provider is required with --scip");
                }
                if (forceFull || dryRun) {
                    throw new IllegalArgumentException("--force-full/--dry-run cannot be combined with --scip");
                }
            } else if (providerVersion != null || module != null || snapshot != null) {
                throw new IllegalArgumentException("--provider-version/--module/--snapshot require --scip");
            }
            return new Options(project, provider, forceFull, dryRun, format, scip, providerVersion, module, snapshot);
        }

    }
}
