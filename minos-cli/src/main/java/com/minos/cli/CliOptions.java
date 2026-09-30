package com.minos.cli;

import com.minos.output.SymbolOutputFormat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The single option parser of the MINOS command line (Q11).
 *
 * <p>Every command declares its options in a {@link Spec} and receives the result as a
 * {@link CliOptions}; no command parses its own arguments. The rules are the same everywhere:</p>
 * <ul>
 *   <li>an option name is matched exactly and is case-sensitive ({@code --FORMAT} is unknown); the
 *       <em>values</em> of enumerated options are compared with {@link java.util.Locale#ROOT} by
 *       their own parsers (for instance {@link SymbolOutputFormat#parse});</li>
 *   <li>an argument starting with {@code --} in option position must be a declared option, otherwise
 *       {@code unknown option: <argument>}; any other argument is an operand when the command
 *       accepts one more, otherwise {@code unexpected argument: <argument>}; a single dash in option
 *       position ({@code -x}) is an unknown option, not an operand;</li>
 *   <li>an option given twice is refused ({@code duplicate option}), whether it takes a value or is a flag;</li>
 *   <li>a value is missing when the arguments end, when it is blank, or when it starts with
 *       {@code --} (it is then the next option, never swallowed as a value); a value starting with
 *       a single dash is a value ({@code --limit -5} reaches the number check and is reported as out
 *       of range);</li>
 *   <li>an integer option is checked against its declared bounds while the arguments are analysed,
 *       so an out-of-range value is a usage error before any service is reached;</li>
 *   <li>{@code null} in the argument array is refused.</li>
 * </ul>
 * <p>Only the analysis is allowed to throw {@link IllegalArgumentException} for these rules; it never
 * receives a service, which is what makes "validate everything, then execute" structural.</p>
 */
final class CliOptions {

    /** An option that the command refuses on purpose ({@link Spec#forbid}); its message is fixed text, never an echo of input. */
    static final class ForbiddenOptionException extends IllegalArgumentException {
        private ForbiddenOptionException(String message) {
            super(message);
        }
    }

    /** Kind of a declared option. */
    enum Kind { FLAG, TEXT, INTEGER }

    /** One declared option, exposed so that guards can derive their probes from the declaration. */
    record Declared(String name, Kind kind, int minimum, int maximum) { }

    private final Spec spec;
    private final Map<String, String> values;
    private final Set<String> flags;
    private final List<String> operands;

    private CliOptions(Spec spec, Map<String, String> values, Set<String> flags, List<String> operands) {
        this.spec = spec;
        this.values = values;
        this.flags = flags;
        this.operands = operands;
    }

    static Spec spec() {
        return new Spec();
    }

    /** Whether the flag, or the valued option, was given. */
    boolean has(String option) {
        declared(option);
        return flags.contains(option) || values.containsKey(option);
    }

    /** The value of a text option, or {@code null} when it was not given. */
    String text(String option) {
        declaredAs(option, Kind.TEXT);
        return values.get(option);
    }

    String text(String option, String fallback) {
        String value = text(option);
        return value == null ? fallback : value;
    }

    /** The value of an integer option (already checked against its bounds), or {@code fallback} when absent. */
    int integer(String option, int fallback) {
        declaredAs(option, Kind.INTEGER);
        String value = values.get(option);
        return value == null ? fallback : Integer.parseInt(value);
    }

    /** The value of an integer option (already checked against its bounds), or {@code null} when absent. */
    Integer optionalInteger(String option) {
        declaredAs(option, Kind.INTEGER);
        String value = values.get(option);
        return value == null ? null : Integer.valueOf(value);
    }

    /** The value of {@code --format}, {@link SymbolOutputFormat#TEXT} when absent. */
    SymbolOutputFormat format() {
        String value = text("--format");
        return value == null ? SymbolOutputFormat.TEXT : SymbolOutputFormat.parse(value);
    }

    /** Free operands found among the options, in order (at most {@link Spec#operands(int)}). */
    List<String> operands() {
        return operands;
    }

    private Declared declared(String option) {
        Declared declared = spec.declared.get(option);
        if (declared == null) {
            throw new IllegalStateException("option " + option + " is not declared by this command");
        }
        return declared;
    }

    private void declaredAs(String option, Kind kind) {
        if (declared(option).kind() != kind) {
            throw new IllegalStateException("option " + option + " is not a " + kind + " option");
        }
    }

    /** Declaration of the options of one command (or of one action of a command). */
    static final class Spec {
        private final Map<String, Declared> declared = new LinkedHashMap<>();
        private final Map<String, String> forbidden = new LinkedHashMap<>();
        private int maximumOperands;
        private String scope = "";

        private Spec() { }

        Spec flag(String... names) {
            for (String name : names) declare(new Declared(name, Kind.FLAG, 0, 0));
            return this;
        }

        Spec text(String... names) {
            for (String name : names) declare(new Declared(name, Kind.TEXT, 0, 0));
            return this;
        }

        Spec integer(String name, int minimum, int maximum) {
            if (minimum > maximum) throw new IllegalArgumentException("empty range for " + name);
            declare(new Declared(name, Kind.INTEGER, minimum, maximum));
            return this;
        }

        /** Allows up to {@code maximum} free operands to appear among the options. */
        Spec operands(int maximum) {
            this.maximumOperands = maximum;
            return this;
        }

        /** Names the command family in the unknown/duplicate/unexpected messages ({@code "team "} gives {@code unknown team option}). */
        Spec scope(String scope) {
            this.scope = scope;
            return this;
        }

        /** Refuses {@code name} with {@code message} (a usage error) instead of reporting it as unknown. */
        Spec forbid(String name, String message) {
            forbidden.put(name, message);
            return this;
        }

        /** Declared options, in declaration order. */
        List<Declared> declared() {
            return List.copyOf(declared.values());
        }

        CliOptions parse(String[] arguments, int from) {
            Objects.requireNonNull(arguments, "arguments");
            Map<String, String> values = new LinkedHashMap<>();
            Set<String> flags = new LinkedHashSet<>();
            List<String> operands = new ArrayList<>();
            for (int index = from; index < arguments.length; index++) {
                String argument = arguments[index];
                if (argument == null) {
                    throw new IllegalArgumentException("argument at index " + index + " must not be null");
                }
                if (forbidden.containsKey(argument)) {
                    throw new ForbiddenOptionException(forbidden.get(argument));
                }
                if (!argument.startsWith("--")) {
                    if (argument.startsWith("-")) {
                        throw new IllegalArgumentException("unknown " + scope + "option: " + argument);
                    }
                    if (operands.size() >= maximumOperands || argument.isBlank()) {
                        throw new IllegalArgumentException("unexpected " + scope + "argument: " + argument);
                    }
                    operands.add(argument);
                    continue;
                }
                Declared option = declared.get(argument);
                if (option == null) {
                    throw new IllegalArgumentException("unknown " + scope + "option: " + argument);
                }
                if (flags.contains(argument) || values.containsKey(argument)) {
                    throw new IllegalArgumentException("duplicate " + scope + "option: " + argument);
                }
                if (option.kind() == Kind.FLAG) {
                    flags.add(argument);
                    continue;
                }
                if (index + 1 >= arguments.length) {
                    throw new IllegalArgumentException("missing value for " + argument);
                }
                String value = arguments[++index];
                if (value == null || value.isBlank() || value.startsWith("--")) {
                    throw new IllegalArgumentException("missing value for " + argument);
                }
                if (option.kind() == Kind.INTEGER) {
                    checkInteger(option, value);
                }
                values.put(argument, value);
            }
            return new CliOptions(this, Collections.unmodifiableMap(values), Collections.unmodifiableSet(flags),
                    List.copyOf(operands));
        }

        private static void checkInteger(Declared option, String value) {
            int parsed;
            try {
                parsed = Integer.parseInt(value);
            } catch (NumberFormatException notAnInteger) {
                throw new IllegalArgumentException(option.name() + " must be an integer");
            }
            if (parsed < option.minimum() || parsed > option.maximum()) {
                throw new IllegalArgumentException(
                        option.name() + " must be between " + option.minimum() + " and " + option.maximum());
            }
        }

        private void declare(Declared option) {
            if (!option.name().startsWith("--") || option.name().length() == 2) {
                throw new IllegalArgumentException("not a long option: " + option.name());
            }
            if (declared.putIfAbsent(option.name(), option) != null) {
                throw new IllegalArgumentException("option declared twice: " + option.name());
            }
        }
    }
}
