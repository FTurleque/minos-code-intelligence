package com.minos.cli;

import com.minos.application.ProjectSymbolQuery;
import com.minos.context.SourceExcerpt;
import com.minos.output.CodeSearchRenderer;
import com.minos.output.SymbolOutputFormat;

import java.io.IOException;
import java.util.Objects;

/** Récupération explicite du contenu complet d'un fichier source local. */
public final class GetSourceCommand {

    public static final String NAME = "get-source";
    private static final String USAGE = """
            Usage: minos get-source <project> <file-id> [options]

            Options:
              --format <text|json>     Output format (default: text)
              -h, --help               Show this help
            """.stripTrailing();

    private static final CliOptions.Spec OPTIONS = CliOptions.spec().text("--format");

    private final ProjectSymbolQuery query;

    public GetSourceCommand(ProjectSymbolQuery query) {
        this.query = Objects.requireNonNull(query, "query");
    }

    public int run(String[] arguments, Appendable output, Appendable error) throws IOException {
        return CliCommandSupport.run(arguments, output, error, USAGE, Options::parse, NAME, options -> {
            SourceExcerpt source = query.getSource(options.projectId(), options.fileId());
            output.append(CodeSearchRenderer.renderSource(source, options.format())).append('\n');
            return FindSymbolCommand.SUCCESS;
        });
    }

    public static String usage() {
        return USAGE;
    }

    private record Options(String projectId, String fileId, SymbolOutputFormat format) {
        private static Options parse(String[] arguments) {
            if (arguments.length < 2) {
                throw new IllegalArgumentException("expected <project> and <file-id>");
            }
            String project = CliCommandSupport.operand(arguments[0], "project");
            String fileId = CliCommandSupport.operand(arguments[1], "file-id");
            return new Options(project, fileId, OPTIONS.parse(arguments, 2).format());
        }
    }
}
