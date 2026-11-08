package com.minos.runtime.local.wsl;

import com.minos.diagnostics.PublicErrorMessages;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Lines of the probe mode of the internal worker command ({@code internal wsl-worker --probe}), protocol
 * {@value #PROTOCOL}: one line per jar-level check, {@code <protocol> <id> <OK|MISSING|UNKNOWN> <action>}.
 *
 * <p>Both sides apply the same rule to an action: a text that names a path or looks like a credential is replaced by
 * the fixed action of its check, so that neither the distribution nor a forged line can bring a path into
 * {@code doctor}. Only the jar-level identifiers are read; any other line is ignored.</p>
 */
public final class WslJarProbeProtocol {

    /** Prefix of every probe line. */
    public static final String PROTOCOL = "minos-wsl-probe-v1";
    /** Argument of the internal worker command that selects the probe mode. */
    public static final String PROBE_OPTION = "--probe";
    /** The checks the jar applies in the distribution, in contract order. */
    public static final List<String> CHECKS = List.of(WslCheck.MAVEN, WslCheck.PROJECT_JDK, WslCheck.MAVEN_REPOSITORY);

    private static final String PREFIX = PROTOCOL + " ";
    private static final Map<String, String> FIXED_ACTIONS = Map.of(
            WslCheck.MAVEN, "install Maven 3.9.x in a system directory of the distribution, with its mvn on PATH",
            WslCheck.PROJECT_JDK, "set minos.scip.java.project-jdk in the MINOS configuration of the distribution "
                    + "to a JDK that contains javac",
            WslCheck.MAVEN_REPOSITORY, "set minos.scip.java.maven-repository in the MINOS configuration of the "
                    + "distribution to a dedicated Maven repository");

    private WslJarProbeProtocol() {
    }

    /** The check {@code id}: {@code OK} without a problem, otherwise {@code MISSING} with a path-free action. */
    public static WslCheck check(String id, Optional<String> problem) {
        requireJarCheck(id);
        Objects.requireNonNull(problem, "problem");
        return problem.isEmpty()
                ? WslCheck.ok(id)
                : WslCheck.missing(id, publicAction(id, problem.orElseThrow()));
    }

    /** Every jar-level check unknown, with the same path-free {@code action}. */
    public static List<WslCheck> unknownChecks(String action) {
        return CHECKS.stream().map(id -> WslCheck.unknown(id, publicAction(id, action))).toList();
    }

    /** The probe lines of {@code checks}, one per check. */
    public static String encode(List<WslCheck> checks) {
        StringBuilder lines = new StringBuilder();
        for (WslCheck check : checks) {
            requireJarCheck(check.id());
            lines.append(PREFIX).append(check.id()).append(' ').append(check.state().name());
            if (!check.action().isEmpty()) {
                lines.append(' ').append(check.action());
            }
            lines.append('\n');
        }
        return lines.toString();
    }

    /**
     * The jar-level checks read from the standard output of the probe: the first valid line of each identifier, in
     * contract order. Lines of another protocol, unknown identifiers or states, and an {@code OK} line that carries an
     * action are ignored; a missing identifier is absent from the result.
     */
    public static List<WslCheck> decode(byte[] stdout) {
        Map<String, WslCheck> read = new LinkedHashMap<>();
        for (String line : new String(stdout, StandardCharsets.UTF_8).split("\\R")) {
            parse(line).ifPresent(check -> read.putIfAbsent(check.id(), check));
        }
        List<WslCheck> ordered = new ArrayList<>();
        for (String id : CHECKS) {
            if (read.containsKey(id)) {
                ordered.add(read.get(id));
            }
        }
        return ordered;
    }

    private static Optional<WslCheck> parse(String line) {
        if (!line.startsWith(PREFIX)) {
            return Optional.empty();
        }
        String[] fields = line.substring(PREFIX.length()).strip().split(" ", 3);
        if (fields.length < 2 || !CHECKS.contains(fields[0])) {
            return Optional.empty();
        }
        String id = fields[0];
        String action = fields.length == 3 ? fields[2].strip() : "";
        return switch (fields[1].toUpperCase(Locale.ROOT)) {
            case "OK" -> action.isEmpty() ? Optional.of(WslCheck.ok(id)) : Optional.empty();
            case "MISSING" -> Optional.of(WslCheck.missing(id, publicAction(id, action)));
            case "UNKNOWN" -> Optional.of(WslCheck.unknown(id, publicAction(id, action)));
            default -> Optional.empty();
        };
    }

    /** {@code action} on one line, or the fixed action of {@code id} when it is blank or names a path. */
    private static String publicAction(String id, String action) {
        return PublicErrorMessages.sanitize(action, FIXED_ACTIONS.get(id));
    }

    private static void requireJarCheck(String id) {
        if (!CHECKS.contains(id)) {
            throw new IllegalArgumentException("not a jar-level WSL check: " + id);
        }
    }
}
