package com.minos.cli;

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

    private final ProjectSymbolQuery query;

    public GetSourceCommand(ProjectSymbolQuery query) {
        this.query = Objects.requireNonNull(query, "query");
    }

    public int run(String[] arguments, Appendable output, Appendable error) throws IOException {
        if (arguments.length == 1 && ("--help".equals(arguments[0]) || "-h".equals(arguments[0]))) {
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
            SourceExcerpt source = query.getSource(options.projectId(), options.fileId());
            output.append(CodeSearchRenderer.renderSource(source, options.format())).append('\n');
            return FindSymbolCommand.SUCCESS;
        } catch (Exception exception) {
            error.append("error: get-source failed: ")
                    .append(CliCommandSupport.failureMessage(exception)).append('\n');
            return FindSymbolCommand.EXECUTION_ERROR;
        }
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
            SymbolOutputFormat format = SymbolOutputFormat.TEXT;
            if (arguments.length > 2) {
                if (arguments.length != 4 || !"--format".equals(arguments[2])) {
                    throw new IllegalArgumentException("unknown option: " + arguments[2]);
                }
                format = SymbolOutputFormat.parse(arguments[3]);
            }
            return new Options(project, fileId, format);
        }
    }
}
