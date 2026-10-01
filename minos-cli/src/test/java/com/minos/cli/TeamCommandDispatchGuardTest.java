package com.minos.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Q19, second half of the guard: {@link TeamOperationGuardTest} enumerates {@code OPERATIONS}, so it
 * cannot see an operation handled OUTSIDE the table (an {@code if ("ping".equals(arguments[0]))} placed
 * in {@code run} would reach the service before any validation and leave every other test green).
 * This test scans the source of the instance code of {@link TeamCommand}, the only code that can touch
 * the service or the bearer token (the table and its parsers are static, so the compiler already keeps
 * them away from both), and requires that:
 * <ul>
 *   <li>the service and the token supplier appear only in the single call
 *       {@code invocation.run(service.get(), this::token)};</li>
 *   <li>that call comes after the analysis of the whole argument list and after the parser of the
 *       operation ({@code options.parse}, then {@code parser.parse}, then {@code run});</li>
 *   <li>an operation is looked up in the table, never compared with a literal.</li>
 * </ul>
 */
class TeamCommandDispatchGuardTest {

    private static final String START = "    int run(";
    private static final String END = "    private static Map<String, Operation> operationTable()";

    private static String source() throws IOException {
        for (String candidate : new String[]{
                "src/main/java/com/minos/cli/TeamCommand.java", "minos-cli/src/main/java/com/minos/cli/TeamCommand.java"}) {
            Path path = Path.of(candidate);
            if (Files.isRegularFile(path)) return Files.readString(path, StandardCharsets.UTF_8);
        }
        throw new IOException("TeamCommand.java not found from " + Path.of("").toAbsolutePath());
    }

    /** The instance-level code of the command, from {@code run} to the table (comments removed, literals kept). */
    static String instanceRegion(String source) {
        int start = source.indexOf(START);
        int end = source.indexOf(END);
        assertTrue(start >= 0 && end > start, "the markers of the instance region moved: update this guard");
        return withoutComments(source.substring(start, end));
    }

    static String withoutComments(String code) {
        return code.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }

    private static String withoutStrings(String code) {
        return code.replaceAll("\"(\\\\.|[^\"\\\\])*\"", "\"\"");
    }

    @Test
    void theServiceAndTheTokenAreReachedOnlyByTheSingleInvocationAfterTheAnalysis() throws IOException {
        String region = instanceRegion(source());
        String call = "invocation.run(service.get(), this::token)";
        assertEquals(1, count(region, Pattern.quote(call)), "exactly one service call: " + call);

        String rest = withoutStrings(region.replace(call, ""));
        assertFalse(Pattern.compile("\\bservice\\b").matcher(rest).find(),
                "the service is used outside the single invocation: an operation handled outside the table would"
                        + " reach it before any validation");
        assertFalse(Pattern.compile("\\bbearerToken\\b|\\btoken\\s*\\(").matcher(rest).find(),
                "the bearer token is read outside the single invocation");

        int analysis = region.indexOf("declared.options.parse(");
        int reading = region.indexOf("declared.parser.parse(");
        int running = region.indexOf(call);
        assertTrue(analysis >= 0 && analysis < reading && reading < running,
                "the order must be: analyse every option, read them, then call the service");
    }

    @Test
    void anOperationIsLookedUpInTheTableNeverComparedWithALiteral() throws IOException {
        String region = instanceRegion(source());
        assertEquals(2, count(region, Pattern.quote("OPERATIONS.get(operation)")),
                "the table is consulted by execute (dispatch) and by declaredOptions (the guards) and nowhere else");
        assertEquals(2, count(region, Pattern.quote("OPERATIONS.get(")), "every lookup goes through the operation name");
        Matcher literal = Pattern.compile("\"[a-z][a-z-]*\"\\s*\\.equals\\(|\\.equals\\(\\s*\"[a-z]|\\bcase\\s+\"")
                .matcher(region);
        boolean compared = literal.find();
        assertFalse(compared, () -> "an operation compared with a literal outside the table: " + literal.group());
    }

    @Test
    void theGuardSeesTheBypassItIsMeantToCatch() {
        String bypassed = "    int run(String[] arguments) {\n"
                + "        if (\"ping\".equals(arguments[0])) { return service.tenant(token()); }\n"
                + "        return execute();\n    }\n    private String execute() { return invocation.run(service.get(), this::token); }\n"
                + END;
        String region = instanceRegion(bypassed);
        String rest = withoutStrings(region.replace("invocation.run(service.get(), this::token)", ""));
        assertTrue(Pattern.compile("\\bservice\\b").matcher(rest).find());
        assertTrue(Pattern.compile("\"[a-z][a-z-]*\"\\s*\\.equals\\(").matcher(region).find());
    }

    private static int count(String text, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        int count = 0;
        while (matcher.find()) count++;
        return count;
    }
}
