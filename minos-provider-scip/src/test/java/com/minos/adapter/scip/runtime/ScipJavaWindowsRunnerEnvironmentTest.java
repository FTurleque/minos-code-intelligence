package com.minos.adapter.scip.runtime;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Windows scip-java runner passes paths to the C# shims it compiles through {@code MINOS_*} environment
 * variables. The names live in four places (save, set, read by a shim, restore) of one script that CI never
 * executes: this test reads the script on every OS and checks that the four places agree.
 */
class ScipJavaWindowsRunnerEnvironmentTest {

    private static final String RUNNER = "/com/minos/adapter/scip/runtime/scip-java-windows-runner.ps1";
    private static final Set<String> EXPECTED = Set.of(
            "MINOS_SCIP_JAVA_MAVEN_CMD", "MINOS_SCIP_JAVA_SCIP_JAVAC_EXE", "MINOS_SCIP_JAVA_BASH_EXE",
            "MINOS_SCIP_JAVA_SCIP_JAVAC_SCRIPT");

    private static final Pattern SET_BY_RUNNER = Pattern.compile("\\$env:(MINOS_\\w+)\\s*=");
    private static final Pattern SAVED_BY_RUNNER =
            Pattern.compile("\\[Environment]::GetEnvironmentVariable\\('(MINOS_\\w+)', 'Process'\\)");
    private static final Pattern RESTORED_BY_RUNNER =
            Pattern.compile("\\[Environment]::SetEnvironmentVariable\\('(MINOS_\\w+)', \\$\\w+, 'Process'\\)");
    private static final Pattern SET_BY_SHIM = Pattern.compile("Environment\\.SetEnvironmentVariable\\(\"(MINOS_\\w+)\"");
    private static final Pattern READ_BY_SHIM = Pattern.compile("Environment\\.GetEnvironmentVariable\\(\"(MINOS_\\w+)\"\\)");
    private static final Pattern ANY_NAME = Pattern.compile("\\bMINOS_\\w+");
    private static final Pattern MILESTONE_SEGMENT = Pattern.compile("(?:^|_)[Mm]\\d+(?:_|$)");

    @Test
    void everyVariableTheRunnerSetsIsReadByAShimAndRestored() throws IOException {
        String runner = runner();

        assertEquals(List.of(), violations(runner));
        assertEquals(EXPECTED, names(SET_BY_RUNNER, runner, names(SET_BY_SHIM, runner, new TreeSet<>())));
    }

    @Test
    void noVariableOfTheRunnerCarriesAMilestoneNumber() throws IOException {
        Set<String> found = names(ANY_NAME, runner(), new TreeSet<>());

        assertTrue(found.containsAll(EXPECTED), found.toString());
        assertEquals(Set.of(), found.stream().filter(name -> MILESTONE_SEGMENT.matcher(name).find())
                .collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void aVariableRenamedInOnlyOnePlaceIsRefused() throws IOException {
        String runner = runner();
        String renamedOnceWhereSet = runner.replace(
                "$env:MINOS_SCIP_JAVA_MAVEN_CMD =", "$env:MINOS_SCIP_JAVA_MAVEN_COMMAND =");
        String renamedOnceWhereRead = runner.replace(
                "GetEnvironmentVariable(\"MINOS_SCIP_JAVA_BASH_EXE\")", "GetEnvironmentVariable(\"MINOS_BASH\")");
        String notRestored = runner.replace(
                "[Environment]::SetEnvironmentVariable('MINOS_SCIP_JAVA_SCIP_JAVAC_EXE', $oldJavac, 'Process')", "");

        assertRefused(runner, renamedOnceWhereSet, "MINOS_SCIP_JAVA_MAVEN_COMMAND");
        assertRefused(runner, renamedOnceWhereRead, "MINOS_BASH");
        assertRefused(runner, notRestored, "MINOS_SCIP_JAVA_SCIP_JAVAC_EXE");
    }

    private static void assertRefused(String original, String mutated, String name) {
        assertNotEquals(original, mutated, "the mutation did not apply for " + name);
        List<String> found = violations(mutated);
        assertTrue(found.stream().anyMatch(violation -> violation.contains(name)), found.toString());
    }

    /** One message per broken link between the places that save, set, read and restore a variable. */
    static List<String> violations(String runner) {
        Set<String> setByRunner = names(SET_BY_RUNNER, runner, new TreeSet<>());
        Set<String> setByShim = names(SET_BY_SHIM, runner, new TreeSet<>());
        Set<String> saved = names(SAVED_BY_RUNNER, runner, new TreeSet<>());
        Set<String> restored = names(RESTORED_BY_RUNNER, runner, new TreeSet<>());
        Set<String> read = names(READ_BY_SHIM, runner, new TreeSet<>());
        List<String> found = new ArrayList<>();
        for (String name : setByRunner) {
            require(found, read.contains(name), name + " is set by the runner but no shim reads it");
            require(found, saved.contains(name), name + " is set by the runner but its previous value is not saved");
            require(found, restored.contains(name), name + " is set by the runner but not restored");
        }
        for (String name : setByShim) {
            require(found, read.contains(name), name + " is set by a shim but no shim reads it");
        }
        Set<String> set = new TreeSet<>(setByRunner);
        set.addAll(setByShim);
        for (Set<String> used : List.of(read, saved, restored)) {
            for (String name : used) {
                require(found, set.contains(name), name + " is read, saved or restored but never set");
            }
        }
        return found;
    }

    private static void require(List<String> found, boolean condition, String message) {
        if (!condition && !found.contains(message)) {
            found.add(message);
        }
    }

    private static Set<String> names(Pattern pattern, String text, Set<String> into) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            into.add(matcher.groupCount() == 0 ? matcher.group() : matcher.group(1));
        }
        return into;
    }

    private static String runner() throws IOException {
        try (var stream = ScipJavaWindowsRunnerEnvironmentTest.class.getResourceAsStream(RUNNER)) {
            return new String(Objects.requireNonNull(stream, "runner resource").readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
