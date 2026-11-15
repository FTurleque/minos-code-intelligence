package com.minos.explorer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps the semantic benchmark harness ({@link SemanticExplorerBenchmark}) in step with the code: it runs the
 * harness on a tiny local index and checks that every expected measure is emitted, that every route of the
 * exploration surface is measured, and that a measure that cannot be taken is named with its reason. It asserts
 * no duration.
 */
class SemanticExplorerBenchmarkSmokeTest {

    private static final String DATASET = "local-n300-d32";

    /** The measures the harness emits for one local dataset, section by section, in no particular order. */
    private static final Set<String> EXPECTED_MEASURES = new TreeSet<>(List.of(
            "dataset/documents", "dataset/dimensions", "dataset/writeMillis", "dataset/beyondBuildBudget",
            "projection/documents", "projection/durationMillis", "projection/passes", "projection/vectorPages",
            "projection/vectorsRead", "projection/peakWorkingBytes", "projection/pointCount",
            "projection/candidateCount", "projection/sampled",
            "heap/retainedBeforeFirstCallBytes", "heap/retainedAfterListBytes", "heap/retainedAfterNeighborsBytes",
            "heap/retainedAfterAllPointsBytes")) {
        {
            for (String scenario : List.of("projects", "list-first", "list-deep", "detail", "neighbors",
                    "projection-status", "projection-points", "search")) {
                for (String statistic : List.of("coldMicros", "p50Micros", "p95Micros", "maxMicros",
                        "allocatedBytesPerRequest", "responseBytes")) {
                    add("explorer/" + scenario + "." + statistic);
                }
            }
        }
    };

    private static final Pattern ABSOLUTE_PATH = Pattern.compile("(?i)([a-z]:[\\\\/])|(^|\\s)/[^\\s]");

    @Test
    void everyRouteOfTheSurfaceHasAMeasuredScenario() {
        Set<ExplorerApi.Route> unmeasured = EnumSet.allOf(ExplorerApi.Route.class);
        unmeasured.removeAll(SemanticExplorerBenchmark.MEASURED_ROUTES.keySet());
        assertTrue(unmeasured.isEmpty(), "routes of ExplorerApi without a measured scenario: " + unmeasured);
        assertEquals(EnumSet.allOf(ExplorerApi.Route.class), SemanticExplorerBenchmark.MEASURED_ROUTES.keySet());
    }

    @Test
    void aTinyLocalIndexEmitsEveryExpectedMeasureWithAValue(@TempDir Path temp) {
        Run run = run(temp, UnaryOperator.identity(), "--dataset=local:300");

        assertEquals(0, run.code(), run.err());
        assertEquals(List.of(), run.refused(), run.out());
        assertEquals(EXPECTED_MEASURES, run.measures(DATASET), run.out());
        assertEquals(EXPECTED_MEASURES.size(), run.metricLines().size(), "a measure emitted twice: " + run.out());
        for (String line : run.metricLines()) {
            String value = line.split("\t", -1)[4];
            assertTrue(value.matches("-?[0-9]+"), "a measure without an integer value: " + line);
        }
        assertTrue(run.out().contains("METRIC\tdataset\t" + DATASET + "\tdocuments\t300"), run.out());
        assertTrue(run.out().contains("METRIC\tprojection\t" + DATASET + "\tpointCount\t300"), run.out());
    }

    @Test
    void aPostgresqlDatasetWithoutUrlIsNamedAndTheLocalDatasetIsStillMeasured(@TempDir Path temp) {
        Run run = run(temp, UnaryOperator.identity(), "--dataset=local:200", "--dataset=postgresql:200");

        assertNotEquals(0, run.code(), run.out());
        assertEquals(List.of("REFUSED\tdataset\tpostgresql-n200-d32\tmissing parameter --postgres-url",
                        "REFUSED\tprojection\tpostgresql-n200-d32\tmissing parameter --postgres-url",
                        "REFUSED\texplorer\tpostgresql-n200-d32\tmissing parameter --postgres-url",
                        "REFUSED\theap\tpostgresql-n200-d32\tmissing parameter --postgres-url"),
                run.refused(), run.out());
        assertFalse(run.measures("local-n200-d32").isEmpty(), run.out());
        assertTrue(run.measures("postgresql-n200-d32").isEmpty(), run.out());
    }

    @Test
    void aPreparationOverItsMemoryBudgetIsRefusedWithTheMotiveOfTheService(@TempDir Path temp) {
        Run run = run(temp, UnaryOperator.identity(), "--dataset=local:300", "--max-memory-bytes=1");

        assertNotEquals(0, run.code(), run.out());
        assertEquals(1, run.refused().size(), run.out());
        String refusal = run.refused().getFirst();
        assertTrue(refusal.startsWith("REFUSED\tprojection\t" + DATASET + "\tMEMORY_BUDGET_EXCEEDED: "), refusal);
        assertTrue(run.measures(DATASET).stream().noneMatch(name -> name.startsWith("projection/")), run.out());
        assertTrue(run.measures(DATASET).contains("explorer/list-first.p50Micros"), run.out());
    }

    @Test
    void anExpectedRefusalThatIsObservedIsNamedAndEndsWithAZeroCode(@TempDir Path temp) {
        Run run = run(temp, SemanticExplorerBenchmarkSmokeTest::refuseTheDecodingRoutes,
                "--dataset=local:300:expect-refusal=DOCUMENTS+DOCUMENT+NEIGHBORS+SEARCH");

        assertEquals(0, run.code(), run.err());
        List<String> scenarios = run.refused().stream().map(line -> line.split("\t", -1)[3])
                .map(reason -> reason.substring(0, reason.indexOf(':'))).toList();
        assertEquals(List.of("list-first", "list-deep", "detail", "neighbors", "search"), scenarios, run.out());
        for (String line : run.refused()) {
            assertTrue(line.contains("http 500 INTERNAL_ERROR"), "a refusal without the answer of the product: " + line);
        }
        for (String scenario : List.of("list-first", "list-deep", "detail", "neighbors", "search")) {
            assertTrue(run.measures(DATASET).stream().noneMatch(name -> name.startsWith("explorer/" + scenario + ".")),
                    scenario + " has a latency although it was refused: " + run.out());
        }
        assertTrue(run.measures(DATASET).contains("explorer/projection-points.p50Micros"), run.out());
    }

    @Test
    void anExpectedRefusalThatNoLongerHappensEndsWithANonZeroCodeAndAsksToRemoveIt(@TempDir Path temp) {
        Run run = run(temp, UnaryOperator.identity(),
                "--dataset=local:300:expect-refusal=DOCUMENTS+DOCUMENT+NEIGHBORS+SEARCH");

        assertNotEquals(0, run.code(), run.out());
        assertEquals(List.of(), run.refused(), run.out());
        assertTrue(run.measures(DATASET).contains("explorer/list-first.p50Micros"), run.out());
        assertTrue(run.err().contains("remove DOCUMENTS from the expected refusals of " + DATASET), run.err());
    }

    @Test
    void twoRunsWithTheSameSeedGiveTheSameDocumentsPassesAndVectorsRead(@TempDir Path temp) {
        Run first = run(temp.resolve("first"), UnaryOperator.identity(), "--dataset=local:300");
        Run second = run(temp.resolve("second"), UnaryOperator.identity(), "--dataset=local:300");

        assertEquals(0, first.code(), first.err());
        assertEquals(0, second.code(), second.err());
        for (String measure : List.of("dataset/documents", "projection/documents", "projection/passes",
                "projection/vectorsRead", "projection/vectorPages", "projection/pointCount")) {
            String value = first.value(DATASET, measure);
            assertFalse(value.isEmpty(), measure + " missing: " + first.out());
            assertEquals(value, second.value(DATASET, measure), measure + " differs between two runs of the same seed");
        }
    }

    @Test
    void noRefusalReasonCarriesAnAbsolutePathOrASecret(@TempDir Path temp) throws Exception {
        Path secret = temp.resolve("postgres-secret.txt");
        Files.writeString(secret, "s3cr3t-value", StandardCharsets.UTF_8);
        Run run = run(temp, SemanticExplorerBenchmarkSmokeTest::refuseTheDecodingRoutes,
                "--dataset=local:200:expect-refusal=DOCUMENTS+DOCUMENT+NEIGHBORS+SEARCH", "--dataset=postgresql:200",
                "--postgres-url=jdbc:postgresql://127.0.0.1:1/bench?password=s3cr3t-value",
                "--postgres-user=bench", "--postgres-password-file=" + secret, "--max-memory-bytes=1");

        assertFalse(run.refused().isEmpty(), run.out());
        for (String line : run.refused()) {
            String reason = line.split("\t", -1)[3];
            assertFalse(reason.contains("s3cr3t-value"), line);
            assertFalse(reason.contains(temp.toString()), line);
            assertFalse(reason.contains("jdbc:"), line);
            assertFalse(ABSOLUTE_PATH.matcher(reason).find(), line);
        }
        assertFalse(run.out().contains("s3cr3t-value"), run.out());
        assertFalse(run.err().contains("s3cr3t-value"), run.err());
    }

    /** Answers the four routes that decode the whole local index as the surface answers a store failure. */
    private static ExplorerServer.Calls refuseTheDecodingRoutes(ExplorerServer.Calls real) {
        return (method, segments, parameters, body, failures) -> {
            ExplorerApi.Route route = ExplorerApi.Route.matching(segments);
            if (route == ExplorerApi.Route.DOCUMENTS || route == ExplorerApi.Route.DOCUMENT
                    || route == ExplorerApi.Route.NEIGHBORS || route == ExplorerApi.Route.SEARCH) {
                return ExplorerApi.Answer.error(500, "INTERNAL_ERROR", ExplorerApi.INTERNAL_ERROR);
            }
            return real.answer(method, segments, parameters, body, failures);
        };
    }

    private static Run run(Path temp, UnaryOperator<ExplorerServer.Calls> calls, String... datasets) {
        List<String> arguments = new ArrayList<>(List.of("--work=" + temp.resolve("work"), "--dimensions=32",
                "--seed=7", "--warmup=0", "--iterations=1", "--deep-page=3", "--run-id=smoke"));
        arguments.addAll(Arrays.asList(datasets));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code;
        try (PrintStream outStream = new PrintStream(out, true, StandardCharsets.UTF_8);
             PrintStream errStream = new PrintStream(err, true, StandardCharsets.UTF_8)) {
            code = SemanticExplorerBenchmark.run(arguments, outStream, errStream, calls);
        }
        return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private record Run(int code, String out, String err) {
        List<String> lines() {
            return out.lines().toList();
        }

        List<String> metricLines() {
            return lines().stream().filter(line -> line.startsWith("METRIC\t")).toList();
        }

        List<String> refused() {
            return lines().stream().filter(line -> line.startsWith("REFUSED\t")).toList();
        }

        Set<String> measures(String dataset) {
            Set<String> names = new TreeSet<>();
            for (String line : metricLines()) {
                String[] fields = line.split("\t", -1);
                assertEquals(5, fields.length, line);
                if (fields[2].equals(dataset)) names.add(fields[1] + "/" + fields[3]);
            }
            return names;
        }

        String value(String dataset, String measure) {
            for (String line : metricLines()) {
                String[] fields = line.split("\t", -1);
                if (fields[2].equals(dataset) && (fields[1] + "/" + fields[3]).equals(measure)) return fields[4];
            }
            return "";
        }
    }
}
