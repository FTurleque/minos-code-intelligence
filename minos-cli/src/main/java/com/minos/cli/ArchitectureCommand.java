package com.minos.cli;

import com.minos.architecture.ArchitectureIntelligenceView;
import com.minos.architecture.ProjectArchitectureQuery;
import com.minos.output.ArchitectureResultRenderer;
import com.minos.output.SymbolOutputFormat;

import java.io.IOException;
import java.util.Objects;

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

    private static final CliOptions.Spec OPTIONS = CliOptions.spec().text("--module", "--format");

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
            String project = CliCommandSupport.operand(arguments[0], "project");
            CliOptions options = OPTIONS.parse(arguments, 1);
            String format = options.text("--format");
            return new Options(project, options.text("--module"),
                    format == null ? ArchitectureOutputFormat.TEXT : ArchitectureOutputFormat.parse(format));
        }
    }
}
