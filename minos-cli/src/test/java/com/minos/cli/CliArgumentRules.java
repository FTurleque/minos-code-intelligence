package com.minos.cli;

import com.minos.application.MinosApplication;
import com.minos.hosted.HostedTenantKeyProvider;

import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The uniform argument rules of the MINOS command line (Q11), checked through the real dispatcher
 * for one command at a time: missing value, value that looks like an option, repeated option,
 * unknown option, stray argument, case, bounds. A command is described once, by
 * {@link #command(String, String)}; every rule is then derived from that description.
 */
final class CliArgumentRules {

    private final MinosApplication application;
    private final List<String> failures = new ArrayList<>();

    CliArgumentRules(Path home) throws IOException {
        this.application = MinosApplication.builder(home).hostedTenantKeyProvider(keys()).build();
    }

    /** A command under test: its base invocation (valid on its own) and its options. */
    static final class Command {
        private final String label;
        private final List<String> base;
        private final List<String[]> texts = new ArrayList<>();
        private final List<Object[]> integers = new ArrayList<>();
        private final List<String> flags = new ArrayList<>();
        private boolean operandsAfterOptions;
        private boolean refusalsOnly;
        private String scope = "";

        private Command(String label, String base) {
            this.label = label;
            this.base = Arrays.asList(base.split(" "));
        }

        /** A free-text option and a sample value that the command's analysis accepts. */
        Command text(String option, String sample) {
            texts.add(new String[]{option, sample, "free"});
            return this;
        }

        /** An enumerated option: its values are case-insensitive, so the upper-cased sample is accepted too. */
        Command choice(String option, String sample) {
            texts.add(new String[]{option, sample, "choice"});
            return this;
        }

        /** An integer option, its inclusive bounds and a valid sample. */
        Command integer(String option, int minimum, int maximum, int sample) {
            integers.add(new Object[]{option, minimum, maximum, sample});
            return this;
        }

        /** A decimal option and a valid sample (its range is the domain's to check, not the analysis'). */
        Command decimal(String option, String sample) {
            texts.add(new String[]{option, sample, "decimal"});
            return this;
        }

        /** An integer option whose range the domain object checks: only its shape is checked at analysis. */
        Command integerAnyRange(String option, int sample) {
            return integer(option, Integer.MIN_VALUE, Integer.MAX_VALUE, sample);
        }

        Command flag(String option) {
            flags.add(option);
            return this;
        }

        /** Names the command family in the unknown/duplicate/unexpected messages, e.g. {@code "team "}. */
        Command scope(String scope) {
            this.scope = scope;
            return this;
        }

        /** Only the refusals are checked: accepted forms would reach a real service with side effects (network, installation). */
        Command refusalsOnly() {
            refusalsOnly = true;
            return this;
        }

        /** The command takes a free operand among its options (a stray argument is then not refused). */
        Command operandsAfterOptions() {
            operandsAfterOptions = true;
            return this;
        }
    }

    static Command command(String label, String base) {
        return new Command(label, base);
    }

    /** Applies every uniform rule to the command. */
    void check(Command command) throws IOException {
        failures.clear();
        checkRules(command);
        assertTrue(failures.isEmpty(), command.label + ": " + failures.size() + " rule(s) broken\n"
                + String.join("\n", failures));
    }

    private void checkRules(Command command) throws IOException {
        for (String[] option : command.texts) {
            String name = option[0];
            String sample = option[1];
            expectUsage(command, "missing value for " + name, name);
            expectUsage(command, "missing value for " + name, name, "--x");
            expectUsage(command, "duplicate " + command.scope + "option: " + name, name, sample, name, sample);
            expectUsage(command, "unknown " + command.scope + "option: " + name.toUpperCase(Locale.ROOT),
                    name.toUpperCase(Locale.ROOT), sample);
            if (option[2].equals("decimal")) {
                expectUsage(command, name + " must be a number", name, "abc");
                assertAccepted(command, name, sample);
            } else if (option[2].equals("choice")) {
                // The value of an enumerated option is case-insensitive (Locale.ROOT).
                assertAccepted(command, name, sample.toUpperCase(Locale.ROOT));
            } else {
                // A value starting with a single dash is a value, not an option.
                assertAccepted(command, name, "-" + sample);
            }
        }
        for (Object[] option : command.integers) {
            String name = (String) option[0];
            int minimum = (Integer) option[1];
            int maximum = (Integer) option[2];
            String sample = Integer.toString((Integer) option[3]);
            expectUsage(command, "missing value for " + name, name);
            expectUsage(command, "missing value for " + name, name, "--x");
            expectUsage(command, "duplicate " + command.scope + "option: " + name, name, sample, name, sample);
            expectUsage(command, "unknown " + command.scope + "option: " + name.toUpperCase(Locale.ROOT),
                    name.toUpperCase(Locale.ROOT), sample);
            expectUsage(command, name + " must be an integer", name, "abc");
            expectUsage(command, name + " must be an integer", name, "-x");
            if (minimum == Integer.MIN_VALUE && maximum == Integer.MAX_VALUE) {
                assertAccepted(command, name, sample);
                continue;
            }
            expectUsage(command, name + " must be between " + minimum + " and " + maximum, name,
                    Integer.toString(minimum - 1));
            expectUsage(command, name + " must be between " + minimum + " and " + maximum, name,
                    Integer.toString(maximum + 1));
            assertAccepted(command, name, Integer.toString(minimum));
            assertAccepted(command, name, Integer.toString(maximum));
        }
        for (String flag : command.flags) {
            expectUsage(command, "duplicate " + command.scope + "option: " + flag, flag, flag);
            expectUsage(command, "unknown " + command.scope + "option: " + flag.toUpperCase(Locale.ROOT), flag.toUpperCase(Locale.ROOT));
        }
        expectUsage(command, "unknown " + command.scope + "option: --bogus", "--bogus", "x");
        expectUsage(command, "unknown " + command.scope + "option: -x", "-x");
        if (!command.operandsAfterOptions) {
            expectUsage(command, "unexpected " + command.scope + "argument: stray", "stray");
        }
    }

    private void expectUsage(Command command, String message, String... extra) throws IOException {
        Outcome outcome = run(command, extra);
        if (outcome.code != 2 || !("error: " + message).equals(outcome.firstErrorLine)) {
            failures.add("  " + String.join(" ", extra) + " -> exit " + outcome.code + " '" + outcome.firstErrorLine
                    + "', expected exit 2 'error: " + message + "'");
        }
    }

    private void assertAccepted(Command command, String... extra) throws IOException {
        if (command.refusalsOnly) {
            return;
        }
        Outcome outcome = run(command, extra);
        if (outcome.code == 2 || outcome.firstErrorLine.startsWith("error: missing value")
                || outcome.firstErrorLine.startsWith("error: unknown option")) {
            failures.add("  " + String.join(" ", extra) + " -> exit " + outcome.code + " '" + outcome.firstErrorLine
                    + "', expected the arguments to be accepted");
        }
    }

    private Outcome run(Command command, String... extra) throws IOException {
        List<String> arguments = new ArrayList<>(command.base);
        arguments.addAll(List.of(extra));
        StringBuilder output = new StringBuilder();
        StringBuilder error = new StringBuilder();
        int code = MinosCliRunner.run(application, arguments.toArray(String[]::new), output, error);
        return new Outcome(code, error.toString().lines().findFirst().orElse(""));
    }

    private record Outcome(int code, String firstErrorLine) { }

    private static HostedTenantKeyProvider keys() {
        return (tenantId, keyId, purpose) -> {
            byte[] bytes = new byte[32];
            Arrays.fill(bytes, (byte) Objects.hash(tenantId, keyId, purpose));
            return new SecretKeySpec(bytes, purpose == HostedTenantKeyProvider.Purpose.ENCRYPTION ? "AES" : "HmacSHA256");
        };
    }
}
