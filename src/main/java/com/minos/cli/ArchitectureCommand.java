package com.minos.cli;

import com.minos.architecture.ArchitectureIntelligenceView;
import com.minos.architecture.ArchitectureModule;
import com.minos.architecture.ArchitectureModuleContext;
import com.minos.architecture.ArchitectureModuleDependency;
import com.minos.architecture.ProjectArchitectureQuery;
import com.minos.domain.Evidence;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Exposition CLI compacte de l'intelligence d'architecture M6, incluant le
 * graphe de dépendances inter-modules réellement dérivé du snapshot actif.
 */
public final class ArchitectureCommand {

    public static final String NAME = "architecture";
    private static final String USAGE = """
            Usage: minos architecture <project> [options]

            Options:
              --module <module>         Return compact context for one module; with a graph format,
                                        keep only the selected module and its direct neighbours
              --format <text|json|mermaid|dot>
                                        Output format (default: text)
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
            String rendered;
            if (options.format().isGraph()) {
                ArchitectureIntelligenceView view = query.getArchitectureIntelligence(options.project());
                String moduleId = options.module() == null
                        ? null
                        : query.getModuleContext(options.project(), options.module()).module().id();
                rendered = renderGraph(view, moduleId, options.format());
            } else if (options.module() == null) {
                rendered = render(query.getArchitectureIntelligence(options.project()), options.format());
            } else {
                rendered = render(query.getModuleContext(options.project(), options.module()), options.format());
            }
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

    private static String render(ArchitectureIntelligenceView view, ArchitectureOutputFormat format) {
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
        map.put("moduleDependencies", dependencyMaps(view));
        if (format == ArchitectureOutputFormat.JSON) {
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
                "moduleEdges: " + view.dependencies().moduleEdgeCount(),
                "topIncomingModules: " + view.centrality().topIncomingModuleIds(),
                "topOutgoingModules: " + view.centrality().topOutgoingModuleIds(),
                "technologies: " + view.technologies().technologies().stream().map(value -> value.name()).toList()
        );
    }

    private static String render(ArchitectureModuleContext context, ArchitectureOutputFormat format) {
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
        if (format == ArchitectureOutputFormat.JSON) {
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

    private static String renderGraph(
            ArchitectureIntelligenceView view,
            String moduleId,
            ArchitectureOutputFormat format
    ) {
        GraphSelection selection = selectGraph(view, moduleId);
        return switch (format) {
            case MERMAID -> renderMermaid(view, selection);
            case DOT -> renderDot(view, selection);
            default -> throw new IllegalArgumentException("graph renderer requires mermaid or dot format");
        };
    }

    private static GraphSelection selectGraph(ArchitectureIntelligenceView view, String moduleId) {
        List<ArchitectureModuleDependency> orderedEdges = view.dependencies().dependencies().stream()
                .sorted(Comparator.comparing(ArchitectureModuleDependency::sourceModuleId)
                        .thenComparing(ArchitectureModuleDependency::targetModuleId)
                        .thenComparing(ArchitectureModuleDependency::id))
                .toList();

        Set<String> includedIds = new LinkedHashSet<>();
        List<ArchitectureModuleDependency> edges = new ArrayList<>();
        if (moduleId == null) {
            view.overview().modules().forEach(module -> includedIds.add(module.id()));
            edges.addAll(orderedEdges);
        } else {
            includedIds.add(moduleId);
            for (ArchitectureModuleDependency edge : orderedEdges) {
                if (moduleId.equals(edge.sourceModuleId()) || moduleId.equals(edge.targetModuleId())) {
                    edges.add(edge);
                    includedIds.add(edge.sourceModuleId());
                    includedIds.add(edge.targetModuleId());
                }
            }
        }

        List<ArchitectureModule> modules = view.overview().modules().stream()
                .filter(module -> includedIds.contains(module.id()))
                .sorted(Comparator.comparing(ArchitectureModule::relativePath)
                        .thenComparing(ArchitectureModule::name)
                        .thenComparing(ArchitectureModule::id))
                .toList();
        return new GraphSelection(modules, List.copyOf(edges));
    }

    private static String renderMermaid(ArchitectureIntelligenceView view, GraphSelection selection) {
        Map<String, String> aliases = aliases(selection.modules());
        StringBuilder result = new StringBuilder();
        result.append("flowchart LR\n");
        result.append("  %% MINOS project: ").append(mermaidText(view.projectName()))
                .append(" | snapshot: ").append(mermaidText(view.snapshotId())).append('\n');
        for (ArchitectureModule module : selection.modules()) {
            String label = module.relativePath().isBlank()
                    ? module.name()
                    : module.name() + "<br/>" + module.relativePath();
            result.append("  ").append(aliases.get(module.id()))
                    .append("[\"").append(mermaidText(label)).append("\"]\n");
        }
        for (ArchitectureModuleDependency edge : selection.edges()) {
            result.append("  ").append(aliases.get(edge.sourceModuleId()))
                    .append(" -->|\"").append(edge.dependencyCount()).append(" deps\"| ")
                    .append(aliases.get(edge.targetModuleId())).append('\n');
        }
        return result.toString().stripTrailing();
    }

    private static String renderDot(ArchitectureIntelligenceView view, GraphSelection selection) {
        StringBuilder result = new StringBuilder();
        result.append("digraph minos_architecture {\n");
        result.append("  graph [label=\"").append(dotText(view.projectName()))
                .append(" @ ").append(dotText(view.snapshotId()))
                .append("\", labelloc=\"t\", rankdir=\"LR\"];\n");
        result.append("  node [shape=box];\n");
        for (ArchitectureModule module : selection.modules()) {
            String label = module.relativePath().isBlank()
                    ? module.name()
                    : module.name() + "\n" + module.relativePath();
            result.append("  \"").append(dotText(module.id())).append("\" [label=\"")
                    .append(dotText(label)).append("\"];\n");
        }
        for (ArchitectureModuleDependency edge : selection.edges()) {
            result.append("  \"").append(dotText(edge.sourceModuleId())).append("\" -> \"")
                    .append(dotText(edge.targetModuleId())).append("\" [label=\"")
                    .append(edge.dependencyCount()).append(" deps\"];\n");
        }
        result.append('}');
        return result.toString();
    }

    private static Map<String, String> aliases(List<ArchitectureModule> modules) {
        Map<String, String> aliases = new LinkedHashMap<>();
        for (int index = 0; index < modules.size(); index++) {
            aliases.put(modules.get(index).id(), "m" + index);
        }
        return aliases;
    }

    private static List<Map<String, Object>> dependencyMaps(ArchitectureIntelligenceView view) {
        Map<String, ArchitectureModule> modulesById = new LinkedHashMap<>();
        view.overview().modules().forEach(module -> modulesById.put(module.id(), module));
        return view.dependencies().dependencies().stream()
                .sorted(Comparator.comparing(ArchitectureModuleDependency::sourceModuleId)
                        .thenComparing(ArchitectureModuleDependency::targetModuleId)
                        .thenComparing(ArchitectureModuleDependency::id))
                .map(edge -> dependencyMap(edge, modulesById))
                .toList();
    }

    private static Map<String, Object> dependencyMap(
            ArchitectureModuleDependency edge,
            Map<String, ArchitectureModule> modulesById
    ) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", edge.id());
        map.put("sourceModuleId", edge.sourceModuleId());
        map.put("sourceModuleName", moduleName(modulesById, edge.sourceModuleId()));
        map.put("targetModuleId", edge.targetModuleId());
        map.put("targetModuleName", moduleName(modulesById, edge.targetModuleId()));
        map.put("dependencyCount", edge.dependencyCount());
        map.put("sourceSymbolCount", edge.sourceSymbolCount());
        map.put("targetSymbolCount", edge.targetSymbolCount());
        map.put("sampleDependencyIds", edge.sampleDependencyIds());
        map.put("nature", edge.nature().name());
        map.put("confidence", edge.confidence());
        map.put("evidence", edge.evidence().stream().map(ArchitectureCommand::evidenceMap).toList());
        return map;
    }

    private static String moduleName(Map<String, ArchitectureModule> modulesById, String moduleId) {
        ArchitectureModule module = modulesById.get(moduleId);
        return module == null ? null : module.name();
    }

    private static Map<String, Object> evidenceMap(Evidence evidence) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", evidence.type().name());
        map.put("description", evidence.description());
        map.put("weight", evidence.weight());
        return map;
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

    private static String mermaidText(String value) {
        return value.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("|", "&#124;")
                .replace("\r", " ")
                .replace("\n", " ");
    }

    private static String dotText(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", " ")
                .replace("\n", "\\n");
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

    private record GraphSelection(
            List<ArchitectureModule> modules,
            List<ArchitectureModuleDependency> edges
    ) {
        private GraphSelection {
            modules = List.copyOf(modules);
            edges = List.copyOf(edges);
        }
    }

    private record Options(String project, String module, ArchitectureOutputFormat format) {
        private static Options parse(String[] arguments) {
            if (arguments.length < 1) {
                throw new IllegalArgumentException("expected <project>");
            }
            String project = operand(arguments[0], "project");
            String module = null;
            ArchitectureOutputFormat format = ArchitectureOutputFormat.TEXT;
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
                    format = ArchitectureOutputFormat.parse(arguments[index]);
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
