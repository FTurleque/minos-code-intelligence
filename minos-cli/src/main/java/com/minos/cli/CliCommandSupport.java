package com.minos.cli;

import com.minos.application.ProjectOperations;
import com.minos.diagnostics.PublicErrorMessages;
import com.minos.domain.SymbolKind;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

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

    private static final String REDACTED_DIAGNOSTIC = "internal diagnostic redacted";

    private CliCommandSupport() {
    }

    /**
     * The JSON object of an import outcome, shared by {@code import-scip} and {@code index}: the keys and their
     * order are decided here once. {@code diagnostic} is the already redacted diagnostic.
     */
    static Map<String, Object> importResultMap(ProjectOperations.IndexImportResult result, String diagnostic) {
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
        map.put("diagnostic", diagnostic);
        return map;
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

    /** Failure line {@code <label> failed: <message>} reporting the originating cause of nested runtime wrappers. */
    static <O> FailureReporter<O> reportingCause(String label) {
        return (options, exception) -> label + " failed: " + failureMessage(unwrapRuntime(exception));
    }

    static boolean isHelp(String value) {
        return "--help".equals(value) || "-h".equals(value);
    }

    /** Whether {@code value} can be an operand: not blank and not starting with a dash (it would be an option). */
    static boolean isOperand(String value) {
        return value != null && !value.isBlank() && !value.startsWith("-");
    }

    static String operand(String value, String name) {
        if (!isOperand(value)) {
            throw new IllegalArgumentException("invalid <" + name + "> operand");
        }
        return value;
    }

    /** The symbol kind named by a {@code --kind} value (case-insensitive, {@code -} for {@code _}); {@code null} when absent. */
    static SymbolKind symbolKind(String value) {
        if (value == null) {
            return null;
        }
        try {
            return SymbolKind.valueOf(value.toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("unsupported symbol kind: " + value, exception);
        }
    }

    static String failureMessage(Throwable failure) {
        return PublicErrorMessages.sanitize(failure.getMessage(), failure.getClass().getSimpleName());
    }

    static String publicDiagnostic(String diagnostic) {
        return diagnostic == null ? null : PublicErrorMessages.sanitize(diagnostic, REDACTED_DIAGNOSTIC);
    }

    static List<String> publicDiagnostics(List<String> diagnostics) {
        return List.copyOf(diagnostics).stream().map(CliCommandSupport::publicDiagnostic).toList();
    }

    static Throwable unwrapRuntime(Throwable failure) {
        Throwable effective = failure;
        while (effective instanceof RuntimeException && effective.getCause() != null) {
            effective = effective.getCause();
        }
        return effective;
    }
}
