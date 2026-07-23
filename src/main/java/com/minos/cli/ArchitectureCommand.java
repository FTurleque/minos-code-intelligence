package com.minos.cli;

import com.minos.architecture.ArchitectureIntelligenceView;
import com.minos.architecture.ArchitectureModule;
import com.minos.architecture.ArchitectureModuleContext;
import com.minos.architecture.ProjectArchitectureQuery;
import com.minos.output.SymbolOutputFormat;

import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Exposition CLI compacte de l'intelligence d'architecture M6.
 */
public final class ArchitectureCommand {

    public static final String NAME = "architecture";
    private static final String USAGE = """
            Usage: minos architecture <project> [options]

            Options:
              --module <module>         Return compact context for one module
              --format <text|json>      Output format (default: text)
              -h, --help                Show this help
            """.stripTrailing();

    private final ProjectArchitectureQuery query;

    public ArchitectureCommand(ProjectArchitectureQuery query) {
        this.query = Objects.requireNonNull(query, "query");
    }

    public int run(String[] arguments, Appendable output, Appendable error) throws IOException {
        if (arguments.length == 1 && isHelp(arguments[0])) {
            output.append(USAGE).append('\n');
            return FindSymbolCommand.SUCCESS;
        }
        Options options;
        try {
            options = Options.parse(arguments);
        } catch (IllegalArgumentException exception) {
            error.append("error: ").append(exception.getMessage()).append('\n')
                    .append(USAGE).append('\n');
            return FindSymbolCommand.USAGE_ERROR;
        }
        try {
            String rendered = options.module() == null
                    ? render(query.getArchitectureIntelligence(options.project()), options.format())
                    : render(query.getModuleContext(options.project(), options.module()), options.format());
            output.append(rendered).append('\n');
            return FindSymbolCommand.SUCCESS;
        } catch (Exception exception) {
            error.append("error: architecture failed: ")
                    .append(failureMessage(exception)).append('\n');
            return FindSymbolCommand.EXECUTION_ERROR;
        }
    }

    public static String usage() {
        return USAGE;
    }

    private static String render(ArchitectureIntelligenceView view, SymbolOutputFormat format) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("projectId", view.projectId());
        map.put("projectName", view.projectName());
        map.put("snapshotId", view.snapshotId());
        map.put("nature", view.nature().name());
        map.put("languages", view.overview().languages());
        map.put("buildSystems", view.overview().buildSystems());
        map.put("moduleCount", view.overview().moduleCount());
        map.put("localSymbolCount", view.overview().localSymbolCount());
        map.put("externalSymbolCount", view.overview().externalSymbolCount());
        map.put("relationshipCount", view.overview().relationshipCount());
        Map<String, Object> dependencies = new LinkedHashMap<>();
        dependencies.put("total", view.dependencies().totalDependencyCount());
        dependencies.put("interModule", view.dependencies().interModuleDependencyCount());
        dependencies.put("intraModule", view.dependencies().intraModuleDependencyCount());
        dependencies.put("unassigned", view.dependencies().unassignedDependencyCount());
        dependencies.put("moduleEdges", view.dependencies().moduleEdgeCount());
        map.put("dependencies", dependencies);
        map.put("topIncomingModuleIds", view.centrality().topIncomingModuleIds());
        map.put("topOutgoingModuleIds", view.centrality().topOutgoingModuleIds());
        map.put("technologies", view.technologies().technologies().stream().map(value -> value.name()).toList());
        map.put("modules", view.overview().modules().stream().map(ArchitectureCommand::moduleMap).toList());
        if (format == SymbolOutputFormat.JSON) {
            return CliJson.render(map);
        }
        return String.join("\n",
                "project: " + view.projectName() + " (" + view.projectId() + ")",
                "snapshot: " + view.snapshotId(),
                "modules: " + view.overview().moduleCount(),
                "languages: " + view.overview().languages(),
                "buildSystems: " + view.overview().buildSystems(),
                "symbols: local=" + view.overview().localSymbolCount() + ", external=" + view.overview().externalSymbolCount(),
                "relationships: " + view.overview().relationshipCount(),
                "dependencies: total=" + view.dependencies().totalDependencyCount()
                        + ", inter=" + view.dependencies().interModuleDependencyCount()
                        + ", intra=" + view.dependencies().intraModuleDependencyCount()
                        + ", unassigned=" + view.dependencies().unassignedDependencyCount(),
                "topIncomingModules: " + view.centrality().topIncomingModuleIds(),
                "topOutgoingModules: " + view.centrality().topOutgoingModuleIds(),
                "technologies: " + view.technologies().technologies().stream().map(value -> value.name()).toList()
        );
    }

    private static String render(ArchitectureModuleContext context, SymbolOutputFormat format) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("projectId", context.projectId());
        map.put("snapshotId", context.snapshotId());
        map.put("nature", context.nature().name());
        map.put("module", moduleMap(context.module()));
        map.put("incomingModuleEdgeCount", context.incomingModuleEdgeCount());
        map.put("outgoingModuleEdgeCount", context.outgoingModuleEdgeCount());
        map.put("incomingDependencyCount", context.concentration().incomingDependencyCount());
        map.put("outgoingDependencyCount", context.concentration().outgoingDependencyCount());
        map.put("incomingRank", context.centrality().incomingRank());
        map.put("outgoingRank", context.centrality().outgoingRank());
        map.put("technologies", context.technologies().stream().map(value -> value.name()).toList());
        if (format == SymbolOutputFormat.JSON) {
            return CliJson.render(map);
        }
        return String.join("\n",
                "module: " + context.module().name() + " (" + context.module().id() + ")",
                "path: " + context.module().relativePath(),
                "snapshot: " + context.snapshotId(),
                "symbols: " + context.module().symbolCount(),
                "incomingModuleEdges: " + context.incomingModuleEdgeCount(),
                "outgoingModuleEdges: " + context.outgoingModuleEdgeCount(),
                "incomingDependencies: " + context.concentration().incomingDependencyCount(),
                "outgoingDependencies: " + context.concentration().outgoingDependencyCount(),
                "incomingRank: " + context.centrality().incomingRank(),
                "outgoingRank: " + context.centrality().outgoingRank(),
                "technologies: " + context.technologies().stream().map(value -> value.name()).toList()
        );
    }

    private static Map<String, Object> moduleMap(ArchitectureModule module) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", module.id());
        map.put("name", module.name());
        map.put("relativePath", module.relativePath());
        map.put("buildSystems", module.buildSystems());
        map.put("languages", module.languages());
        map.put("sourceRootCount", module.sourceRootCount());
        map.put("symbolCount", module.symbolCount());
        map.put("namespaceCount", module.namespaceCount());
        return map;
    }

    private static boolean isHelp(String value) {
        return "--help".equals(value) || "-h".equals(value);
    }

    private static String failureMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message.replace('\r', ' ').replace('\n', ' ');
    }

    private record Options(String project, String module, SymbolOutputFormat format) {
        private static Options parse(String[] arguments) {
            if (arguments.length < 1) {
                throw new IllegalArgumentException("expected <project>");
            }
            String project = operand(arguments[0], "project");
            String module = null;
            SymbolOutputFormat format = SymbolOutputFormat.TEXT;
            Set<String> seen = new HashSet<>();
            for (int index = 1; index < arguments.length; index++) {
                String option = arguments[index];
                if (!Set.of("--module", "--format").contains(option)) {
                    throw new IllegalArgumentException("unknown option: " + option);
                }
                if (!seen.add(option)) {
                    throw new IllegalArgumentException("duplicate option: " + option);
                }
                if (++index >= arguments.length || arguments[index] == null || arguments[index].isBlank()) {
                    throw new IllegalArgumentException("missing value for " + option);
                }
                if ("--module".equals(option)) {
                    module = arguments[index];
                } else {
                    format = SymbolOutputFormat.parse(arguments[index]);
                }
            }
            return new Options(project, module, format);
        }

        private static String operand(String value, String name) {
            if (value == null || value.isBlank() || value.startsWith("-")) {
                throw new IllegalArgumentException("invalid <" + name + "> operand");
            }
            return value;
        }
    }
}
