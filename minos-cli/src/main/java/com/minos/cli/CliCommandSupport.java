package com.minos.cli;

import java.io.IOException;

/**
 * Shared fail-closed skeleton for the option-parsing MINOS commands.
 *
 * <p>Every such command honours the same contract:</p>
 * <ul>
 *   <li>a lone {@code --help}/{@code -h} prints the usage and returns
 *       {@link FindSymbolCommand#SUCCESS};</li>
 *   <li>an argument list the command cannot parse prints {@code error: <message>} followed by the
 *       usage and returns {@link FindSymbolCommand#USAGE_ERROR};</li>
 *   <li>any failure raised while executing the parsed options prints {@code error: <report>} and
 *       returns {@link FindSymbolCommand#EXECUTION_ERROR}.</li>
 * </ul>
 *
 * <p>Only the parse step is allowed to answer {@code USAGE_ERROR}: a failure surfacing after the
 * options are accepted is an execution failure even when it is an {@link IllegalArgumentException},
 * which is why the two phases are caught separately.</p>
 */
final class CliCommandSupport {

    private CliCommandSupport() {
    }

    @FunctionalInterface
    interface OptionsParser<O> {
        O parse(String[] arguments);
    }

    @FunctionalInterface
    interface CommandBody<O> {
        int execute(O options) throws Exception;
    }

    /** Renders the text placed after {@code "error: "} when the command body fails. */
    @FunctionalInterface
    interface FailureReporter<O> {
        String describe(O options, Exception failure);
    }

    static <O> int run(
            String[] arguments,
            Appendable output,
            Appendable error,
            String usage,
            OptionsParser<O> parser,
            FailureReporter<O> failureReporter,
            CommandBody<O> body
    ) throws IOException {
        if (arguments.length == 1 && isHelp(arguments[0])) {
            output.append(usage).append('\n');
            return FindSymbolCommand.SUCCESS;
        }
        O options;
        try {
            options = parser.parse(arguments);
        } catch (IllegalArgumentException exception) {
            error.append("error: ").append(exception.getMessage()).append('\n').append(usage).append('\n');
            return FindSymbolCommand.USAGE_ERROR;
        }
        try {
            return body.execute(options);
        } catch (Exception exception) {
            error.append("error: ").append(failureReporter.describe(options, exception)).append('\n');
            return FindSymbolCommand.EXECUTION_ERROR;
        }
    }

    /** Variant for commands whose failure line is a fixed {@code <label> failed: <message>}. */
    static <O> int run(
            String[] arguments,
            Appendable output,
            Appendable error,
            String usage,
            OptionsParser<O> parser,
            String label,
            CommandBody<O> body
    ) throws IOException {
        return run(arguments, output, error, usage, parser,
                (options, exception) -> label + " failed: " + failureMessage(exception), body);
    }

    static boolean isHelp(String value) {
        return "--help".equals(value) || "-h".equals(value);
    }

    /**
     * Single-line operator-facing text for a failure: a blank message degrades to the exception type
     * and embedded line breaks are flattened so one failure stays one diagnostic line.
     */
    static String failureMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName()
                : message.replace('\r', ' ').replace('\n', ' ');
    }

    /**
     * Peels runtime wrappers off a failure so the operator sees the originating diagnostic rather
     * than the plumbing that re-threw it. Checked causes are preserved as-is.
     */
    static Throwable unwrapRuntime(Throwable failure) {
        Throwable effective = failure;
        while (effective instanceof RuntimeException && effective.getCause() != null) {
            effective = effective.getCause();
        }
        return effective;
    }
}
