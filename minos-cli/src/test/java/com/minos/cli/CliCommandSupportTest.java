package com.minos.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the contract every option-parsing command inherits from {@link CliCommandSupport}. */
class CliCommandSupportTest {

    private static final String USAGE = "Usage: minos widget <project>";

    private final StringBuilder output = new StringBuilder();
    private final StringBuilder error = new StringBuilder();

    private int run(String[] arguments, CliCommandSupport.CommandBody<String> body) throws IOException {
        return CliCommandSupport.run(arguments, output, error, USAGE, CliCommandSupportTest::parse, "widget", body);
    }

    private static String parse(String[] arguments) {
        if (arguments.length != 1) throw new IllegalArgumentException("expected <project>");
        return arguments[0];
    }

    @Test
    void helpPrintsUsageOnStdoutAndSucceeds() throws Exception {
        assertEquals(FindSymbolCommand.SUCCESS, run(new String[]{"--help"}, options -> {
            throw new AssertionError("body must not run for --help");
        }));
        assertEquals(USAGE + "\n", output.toString());
        assertEquals("", error.toString());

        output.setLength(0);
        assertEquals(FindSymbolCommand.SUCCESS, run(new String[]{"-h"}, options -> {
            throw new AssertionError("body must not run for -h");
        }));
        assertEquals(USAGE + "\n", output.toString());
    }

    @Test
    void unparseableArgumentsReportUsageErrorOnStderr() throws Exception {
        assertEquals(FindSymbolCommand.USAGE_ERROR, run(new String[]{}, options -> {
            throw new AssertionError("body must not run for unparseable arguments");
        }));
        assertEquals("error: expected <project>\n" + USAGE + "\n", error.toString());
        assertEquals("", output.toString());
    }

    @Test
    void parsedOptionsReachTheBodyAndItsExitCodeIsReturned() throws Exception {
        assertEquals(FindSymbolCommand.SUCCESS, run(new String[]{"alpha"}, options -> {
            output.append(options);
            return FindSymbolCommand.SUCCESS;
        }));
        assertEquals("alpha", output.toString());
        assertEquals("", error.toString());
    }

    @Test
    void failureAfterParsingIsAnExecutionErrorEvenWhenItIsAnIllegalArgument() throws Exception {
        assertEquals(FindSymbolCommand.EXECUTION_ERROR, run(new String[]{"alpha"}, options -> {
            throw new IllegalArgumentException("backend refused");
        }));
        assertEquals("error: widget failed: backend refused\n", error.toString());
        assertTrue(output.isEmpty(), "a failing body must not leave partial output expectations");
    }

    @Test
    void blankFailureMessagesDegradeToTheExceptionType() throws Exception {
        assertEquals(FindSymbolCommand.EXECUTION_ERROR, run(new String[]{"alpha"}, options -> {
            throw new IllegalStateException("  ");
        }));
        assertEquals("error: widget failed: IllegalStateException\n", error.toString());
    }

    @Test
    void multiLineFailureMessagesStayOnOneDiagnosticLine() throws Exception {
        assertEquals(FindSymbolCommand.EXECUTION_ERROR, run(new String[]{"alpha"}, options -> {
            throw new IOException("first\r\nsecond");
        }));
        assertEquals("error: widget failed: first  second\n", error.toString());
    }

    @Test
    void sensitiveExecutionMessagesAreRedactedAtTheSharedCliBoundary() throws Exception {
        List<String> sensitive = List.of(
                "cannot open /home/private-user/.minos/token",
                "cannot open C:\\Users\\private-user\\.minos\\token",
                "cannot open \\\\server\\private-share\\token",
                "jdbc:postgresql://user:password@db.example/minos",
                "token=super-secret",
                "password=super-secret",
                "{\"access_token\":\"super-secret\"}",
                "api-key: super-secret",
                "Authorization: Bearer super-secret");
        for (String detail : sensitive) {
            error.setLength(0);
            assertEquals(FindSymbolCommand.EXECUTION_ERROR, run(new String[]{"alpha"}, options -> {
                throw new IOException(detail);
            }));
            assertEquals("error: widget failed: IOException\n", error.toString(), detail);
            assertFalse(error.toString().contains("super-secret"), detail);
            assertFalse(error.toString().contains("private-user"), detail);
        }
    }

    @Test
    void resultDiagnosticsUseTheSameFailClosedPolicy() {
        assertNull(CliCommandSupport.publicDiagnostic(null));
        assertEquals("safe operational note", CliCommandSupport.publicDiagnostic("safe operational note"));
        assertEquals("internal diagnostic redacted",
                CliCommandSupport.publicDiagnostic("failed at /home/private-user/.minos/state"));
        assertEquals("internal diagnostic redacted",
                CliCommandSupport.publicDiagnostic("jdbc:postgresql://user:password@db/minos"));
    }

    @Test
    void unwrapRuntimeReportsTheOriginatingCauseThroughNestedRuntimeWrappers() {
        IOException root = new IOException("disk is gone");
        Throwable wrapped = new IllegalStateException("outer", new UncheckedIOException(root));
        assertEquals(root, CliCommandSupport.unwrapRuntime(wrapped));
        assertEquals("disk is gone", CliCommandSupport.failureMessage(CliCommandSupport.unwrapRuntime(wrapped)));
    }

    @Test
    void unwrapRuntimeKeepsCheckedFailuresAndCauselessRuntimeFailures() {
        IOException checked = new IOException("checked", new IllegalStateException("cause"));
        assertEquals(checked, CliCommandSupport.unwrapRuntime(checked));
        IllegalStateException causeless = new IllegalStateException("causeless");
        assertEquals(causeless, CliCommandSupport.unwrapRuntime(causeless));
    }

    @Test
    void operandRejectsAnythingThatWouldSwallowAFollowingFlag() {
        assertEquals("alpha", CliCommandSupport.operand("alpha", "project"));
        for (String rejected : new String[]{null, "", "   ", "-h", "--limit"}) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> CliCommandSupport.operand(rejected, "project"));
            assertEquals("invalid <project> operand", failure.getMessage());
        }
    }

    @Test
    void parseLimitEnforcesTheInclusiveRangeAndReportsNonNumericInput() {
        assertEquals(1, CliCommandSupport.parseLimit("1", 1000));
        assertEquals(1000, CliCommandSupport.parseLimit("1000", 1000));
        assertEquals("limit must be between 1 and 1000",
                assertThrows(IllegalArgumentException.class,
                        () -> CliCommandSupport.parseLimit("0", 1000)).getMessage());
        assertEquals("limit must be between 1 and 1000",
                assertThrows(IllegalArgumentException.class,
                        () -> CliCommandSupport.parseLimit("1001", 1000)).getMessage());
        assertEquals("invalid limit: abc",
                assertThrows(IllegalArgumentException.class,
                        () -> CliCommandSupport.parseLimit("abc", 1000)).getMessage());
    }

    @Test
    void isHelpAcceptsOnlyTheTwoDocumentedFlags() {
        assertTrue(CliCommandSupport.isHelp("--help"));
        assertTrue(CliCommandSupport.isHelp("-h"));
        assertFalse(CliCommandSupport.isHelp("help"));
        assertFalse(CliCommandSupport.isHelp("-H"));
    }
}
