package com.minos.cli;

import com.minos.cli.TeamFixtures.SpyStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.minos.cli.TeamFixtures.bootstrap;
import static com.minos.cli.TeamFixtures.service;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Q19 guard: "validate everything, then execute" for EVERY team operation, derived from the sources
 * of truth and never from a list copied into the test.
 *
 * <ul>
 *   <li>the operations come from {@link TeamCommand#operations()};</li>
 *   <li>the options of each operation come from {@link TeamCommand#declaredOptions(String)};</li>
 *   <li>the documented bounds come from {@link TeamCommand#usage()}, i.e. from what an operator reads.</li>
 * </ul>
 *
 * <p>For each operation the guard builds an invocation that is valid (and proves it is: it reaches
 * the service), then breaks it one way at a time (unknown option, stray argument, repeated option,
 * missing value, value that looks like an option, non-integer, out-of-range value, bearer token as
 * an option) and requires a usage error (code 2) while the recording store saw no call and the
 * bearer token was never read. An operation added to the table without honouring the rule turns
 * this class red without any edit here, except a sample value for a brand new option name.</p>
 */
class TeamOperationGuardTest {

    /** A valid sample per option NAME; a new option name must get one here (checked below). */
    private static final Map<String, String> SAMPLES = Map.ofEntries(
            Map.entry("--tenant", UUID.randomUUID().toString()),
            Map.entry("--name", "Platform"),
            Map.entry("--key-id", "key-b"),
            Map.entry("--owner", "bob"),
            Map.entry("--owner-name", "Bob"),
            Map.entry("--workspace", UUID.randomUUID().toString()),
            Map.entry("--project", UUID.randomUUID().toString()),
            Map.entry("--snapshot", "snap-1"),
            Map.entry("--principal", "bob"),
            Map.entry("--display-name", "Bob"),
            Map.entry("--role", "viewer"),
            Map.entry("--request-id", "req-guard"),
            Map.entry("--token-hours", "2"),
            Map.entry("--limit", "5"),
            Map.entry("--max-audit-events", "100"),
            Map.entry("--audit-days", "1"),
            Map.entry("--archived-workspace-days", "1"));

    /**
     * A malformed value per option whose parser checks a FORMAT (a UUID, a role): the expected usage message.
     * Every other declared text option must be listed in {@link #FREE_TEXT}, so that a new option gets a
     * conscious classification here.
     */
    private static final Map<String, String[]> MALFORMED = Map.of(
            "--tenant", new String[]{"not-a-uuid", "tenant must be a UUID"},
            "--workspace", new String[]{"not-a-uuid", "workspace must be a UUID"},
            "--project", new String[]{"not-a-uuid", "project must be a UUID"},
            "--role", new String[]{"bogus", "unsupported hosted role: bogus"});

    /** Text options whose value is free (a name, an identifier): any non-blank value is syntactically valid. */
    private static final java.util.Set<String> FREE_TEXT = java.util.Set.of(
            "--name", "--key-id", "--owner", "--owner-name", "--snapshot", "--principal", "--display-name",
            "--request-id");

    private static final Pattern OPERATION_LINE = Pattern.compile("^  ([a-z][a-z-]*)(?:\\s|$)");
    private static final Pattern BOUND = Pattern.compile("(--[a-z-]+) <(\\d+)\\.\\.(\\d+)>");

    private final SpyStore store = new SpyStore();
    private final AtomicInteger tokenReads = new AtomicInteger();
    private final AtomicReference<String> token = new AtomicReference<>();
    private final StringBuilder output = new StringBuilder();
    private final StringBuilder error = new StringBuilder();
    private TeamCommand command;

    @BeforeEach
    void bootstrapATenant() throws IOException {
        command = new TeamCommand(service(store), () -> {
            tokenReads.incrementAndGet();
            return token.get();
        });
        token.set(bootstrap(command, UUID.randomUUID(), output, error));
        resetSpies();
    }

    private void resetSpies() {
        store.calls.clear();
        tokenReads.set(0);
        output.setLength(0);
        error.setLength(0);
    }

    private int run(List<String> arguments) throws IOException {
        resetSpies();
        return command.run(arguments.toArray(String[]::new), output, error);
    }

    private static List<String> validArguments(String operation) {
        List<String> arguments = new ArrayList<>();
        arguments.add(operation);
        for (CliOptions.Declared option : TeamCommand.declaredOptions(operation)) {
            arguments.add(option.name());
            arguments.add(sample(option.name()));
        }
        return arguments;
    }

    private static String sample(String option) {
        String sample = SAMPLES.get(option);
        assertTrue(sample != null, "no sample value for the new team option " + option
                + ": add one to SAMPLES so that the guard can exercise it");
        return sample;
    }

    /** The arguments of {@code operation} with {@code option}'s value replaced (or the option removed when {@code value} is null). */
    private static List<String> with(String operation, String option, String value) {
        List<String> arguments = new ArrayList<>();
        arguments.add(operation);
        for (CliOptions.Declared declared : TeamCommand.declaredOptions(operation)) {
            if (declared.name().equals(option)) {
                if (value == null) continue;
                arguments.add(option);
                arguments.add(value);
            } else {
                arguments.add(declared.name());
                arguments.add(sample(declared.name()));
            }
        }
        return arguments;
    }

    private static List<String> plus(List<String> arguments, String... more) {
        List<String> all = new ArrayList<>(arguments);
        all.addAll(List.of(more));
        return all;
    }

    private void assertUsageErrorBeforeTheService(List<String> arguments, String expectedMessage) throws IOException {
        int code = run(arguments);
        String context = String.join(" ", arguments) + " -> exit " + code + " " + error;
        assertEquals(2, code, context);
        assertEquals("error: " + expectedMessage, error.toString().lines().findFirst().orElse(""), context);
        assertEquals("", output.toString(), context);
        assertEquals(List.of(), store.calls, context + " reached the service");
        assertEquals(0, tokenReads.get(), context + " read the bearer token");
    }

    @Test
    void everySampleNamesAnOptionThatSomeOperationDeclares() {
        List<String> declared = new ArrayList<>();
        for (String operation : TeamCommand.operations()) {
            TeamCommand.declaredOptions(operation).forEach(option -> declared.add(option.name()));
        }
        for (String sample : SAMPLES.keySet()) {
            assertTrue(declared.contains(sample), "SAMPLES has a stale option: " + sample);
        }
    }

    @Test
    void theGuardIsNotVacuousEveryOperationAcceptsItsFullyDeclaredArgumentsAndReachesTheService() throws IOException {
        for (String operation : TeamCommand.operations()) {
            int code = run(validArguments(operation));
            String context = operation + " " + validArguments(operation) + " -> exit " + code + " " + error;
            assertNotEquals(2, code, context);
            assertTrue(!store.calls.isEmpty() || tokenReads.get() > 0, context + ": the service was not reached");
        }
    }

    @Test
    void everyOperationRejectsUnknownOptionsAndStrayArgumentsBeforeTheService() throws IOException {
        for (String operation : TeamCommand.operations()) {
            assertUsageErrorBeforeTheService(plus(validArguments(operation), "--bogus", "x"),
                    "unknown team option: --bogus");
            assertUsageErrorBeforeTheService(plus(validArguments(operation), "-x"), "unknown team option: -x");
            assertUsageErrorBeforeTheService(plus(validArguments(operation), "stray"),
                    "unexpected team argument: stray");
        }
    }

    @Test
    void everyDeclaredOptionRejectsRepetitionMissingValuesAndOptionLikeValuesBeforeTheService() throws IOException {
        for (String operation : TeamCommand.operations()) {
            for (CliOptions.Declared option : TeamCommand.declaredOptions(operation)) {
                String name = option.name();
                assertUsageErrorBeforeTheService(plus(validArguments(operation), name, sample(name)),
                        "duplicate team option: " + name);
                assertUsageErrorBeforeTheService(plus(with(operation, name, null), name),
                        "missing value for " + name);
                assertUsageErrorBeforeTheService(plus(with(operation, name, null), name, "--x"),
                        "missing value for " + name);
                assertUsageErrorBeforeTheService(with(operation, name, "--x"), "missing value for " + name);
                String upper = name.toUpperCase(java.util.Locale.ROOT);
                assertUsageErrorBeforeTheService(plus(with(operation, name, null), upper, sample(name)),
                        "unknown team option: " + upper);
            }
        }
    }

    @Test
    void everyOptionalOrRequiredOptionIsEitherRequiredWithAUsageErrorOrHonouredNeverInBetween() throws IOException {
        for (String operation : TeamCommand.operations()) {
            for (CliOptions.Declared option : TeamCommand.declaredOptions(operation)) {
                List<String> arguments = with(operation, option.name(), null);
                int code = run(arguments);
                String context = String.join(" ", arguments) + " -> exit " + code + " " + error;
                if (code == 2) {
                    assertEquals("error: missing required option: " + option.name(),
                            error.toString().lines().findFirst().orElse(""), context);
                    assertEquals(List.of(), store.calls, context + " reached the service");
                    assertEquals(0, tokenReads.get(), context + " read the bearer token");
                } else {
                    assertTrue(!store.calls.isEmpty() || tokenReads.get() > 0,
                            context + ": neither refused nor executed");
                }
            }
        }
    }

    @Test
    void everyTextOptionIsEitherFreeTextOrHasAFormatThatIsCheckedBeforeTheService() throws IOException {
        for (String operation : TeamCommand.operations()) {
            for (CliOptions.Declared option : TeamCommand.declaredOptions(operation)) {
                if (option.kind() != CliOptions.Kind.TEXT) continue;
                String name = option.name();
                String[] malformed = MALFORMED.get(name);
                assertTrue(malformed != null || FREE_TEXT.contains(name), operation + " declares the text option "
                        + name + ": classify it in MALFORMED (a format is checked) or FREE_TEXT");
                if (malformed != null) {
                    assertUsageErrorBeforeTheService(with(operation, name, malformed[0]), malformed[1]);
                }
            }
        }
    }

    @Test
    void everyIntegerOptionRejectsNonIntegersBeforeTheService() throws IOException {
        for (String operation : TeamCommand.operations()) {
            for (CliOptions.Declared option : TeamCommand.declaredOptions(operation)) {
                if (option.kind() != CliOptions.Kind.INTEGER) continue;
                assertUsageErrorBeforeTheService(with(operation, option.name(), "abc"),
                        option.name() + " must be an integer");
                assertUsageErrorBeforeTheService(with(operation, option.name(), "-x"),
                        option.name() + " must be an integer");
            }
        }
    }

    /** What the usage text documents: {@code operation -> option -> [lo, hi]}. */
    private static Map<String, Map<String, int[]>> documentedBounds() {
        Map<String, Map<String, int[]>> bounds = new LinkedHashMap<>();
        StringBuilder block = null;
        String operation = null;
        for (String line : TeamCommand.usage().lines().toList()) {
            if (line.startsWith("Authentication:")) break;
            Matcher start = OPERATION_LINE.matcher(line);
            if (start.find()) {
                collect(bounds, operation, block);
                operation = start.group(1);
                block = new StringBuilder();
            }
            if (block != null) block.append(line).append('\n');
        }
        collect(bounds, operation, block);
        return bounds;
    }

    private static void collect(Map<String, Map<String, int[]>> bounds, String operation, StringBuilder block) {
        if (operation == null) return;
        Matcher matcher = BOUND.matcher(block);
        while (matcher.find()) {
            bounds.computeIfAbsent(operation, key -> new LinkedHashMap<>())
                    .put(matcher.group(1), new int[]{Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3))});
        }
    }

    @Test
    void theUsageDocumentsBoundsAndTheyAreTheOnesTheOperationsDeclare() {
        Map<String, Map<String, int[]>> documented = documentedBounds();
        assertFalse(documented.isEmpty(), "the usage documents no numeric bound: the parser of this guard is stale");
        for (var operation : documented.entrySet()) {
            assertTrue(TeamCommand.operations().contains(operation.getKey()), operation.getKey());
            for (var bound : operation.getValue().entrySet()) {
                CliOptions.Declared declared = TeamCommand.declaredOptions(operation.getKey()).stream()
                        .filter(option -> option.name().equals(bound.getKey())).findFirst().orElseThrow(
                                () -> new AssertionError(operation.getKey() + " documents " + bound.getKey()
                                        + " but does not declare it"));
                assertEquals(CliOptions.Kind.INTEGER, declared.kind(),
                        operation.getKey() + " " + bound.getKey() + " is documented as a range but is not an integer option");
                assertEquals(bound.getValue()[0], declared.minimum(), operation.getKey() + " " + bound.getKey());
                assertEquals(bound.getValue()[1], declared.maximum(), operation.getKey() + " " + bound.getKey());
            }
        }
    }

    @Test
    void everyBoundDocumentedInTheUsageIsAUsageErrorBeforeTheService() throws IOException {
        int checked = 0;
        for (var operation : documentedBounds().entrySet()) {
            for (var bound : operation.getValue().entrySet()) {
                int low = bound.getValue()[0];
                int high = bound.getValue()[1];
                for (String value : List.of(Integer.toString(low - 1), Integer.toString(high + 1),
                        Integer.toString(Integer.MAX_VALUE), Integer.toString(Integer.MIN_VALUE))) {
                    assertUsageErrorBeforeTheService(with(operation.getKey(), bound.getKey(), value),
                            bound.getKey() + " must be between " + low + " and " + high);
                    checked++;
                }
                // The inclusive ends are accepted (the invocation reaches the service).
                for (int inside : new int[]{low, high}) {
                    int code = run(with(operation.getKey(), bound.getKey(), Integer.toString(inside)));
                    assertNotEquals(2, code, operation.getKey() + " " + bound.getKey() + " " + inside + " -> " + error);
                }
            }
        }
        assertTrue(checked >= 12, "expected the documented bounds of audit, token-issue, key-rotate and retention-set");
    }

    @Test
    void aBearerTokenIsNeverAnOptionAndTheRefusalIsReadable() throws IOException {
        String secret = "must-never-appear";
        for (String operation : TeamCommand.operations()) {
            for (String option : List.of("--token", "--bearer-token")) {
                int code = run(plus(validArguments(operation), option, secret));
                String context = operation + " " + option + " -> " + error;
                assertEquals(2, code, context);
                assertEquals("error: bearer tokens are accepted only through MINOS_TEAM_TOKEN",
                        error.toString().lines().findFirst().orElse(""), context);
                assertFalse(error.toString().contains(secret), context);
                assertEquals(List.of(), store.calls, context);
                assertEquals(0, tokenReads.get(), context);
            }
        }
    }

    @Test
    void anUnknownOperationIsAUsageErrorBeforeTheService() throws IOException {
        assertUsageErrorBeforeTheService(List.of("bogus-operation"), "unknown team operation: bogus-operation");
        assertUsageErrorBeforeTheService(List.of("bogus-operation", "--x", "y"),
                "unknown team operation: bogus-operation");
    }
}
