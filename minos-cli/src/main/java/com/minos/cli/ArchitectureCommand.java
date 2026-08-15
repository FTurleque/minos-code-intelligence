package com.minos.cli;

import com.minos.architecture.ArchitectureIntelligenceView;
import com.minos.architecture.ProjectArchitectureQuery;
import com.minos.output.ArchitectureResultRenderer;
import com.minos.output.SymbolOutputFormat;

import java.io.IOException;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** CLI adapter for M6 architecture intelligence. */
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
        return CliCommandSupport.run(arguments, output, error, USAGE, Options::parse, NAME, options -> {
            String rendered;
            if (options.format().isGraph()) {
                ArchitectureIntelligenceView view = query.getArchitectureIntelligence(options.project());
                String moduleId = options.module() == null
                        ? null
                        : query.getModuleContext(options.project(), options.module()).module().id();
                ArchitectureResultRenderer.GraphFormat graphFormat = switch (options.format()) {
                    case MERMAID -> ArchitectureResultRenderer.GraphFormat.MERMAID;
                    case DOT -> ArchitectureResultRenderer.GraphFormat.DOT;
                    default -> throw new IllegalStateException("non-graph architecture format");
                };
                rendered = ArchitectureResultRenderer.renderGraph(view, moduleId, graphFormat);
            } else {
                SymbolOutputFormat outputFormat = options.format() == ArchitectureOutputFormat.JSON
                        ? SymbolOutputFormat.JSON
                        : SymbolOutputFormat.TEXT;
                rendered = options.module() == null
                        ? ArchitectureResultRenderer.render(
                                query.getArchitectureIntelligence(options.project()), outputFormat)
                        : ArchitectureResultRenderer.renderModule(
                                query.getModuleContext(options.project(), options.module()), outputFormat);
            }
            output.append(rendered).append('\n');
            return FindSymbolCommand.SUCCESS;
        });
    }

    public static String usage() {
        return USAGE;
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
