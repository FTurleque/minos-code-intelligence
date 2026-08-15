package com.minos.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the contract every option-parsing command inherits from {@link CliCommandSupport}, so a
 * change to the shared skeleton cannot silently alter exit codes or operator diagnostics.
 */
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
    void isHelpAcceptsOnlyTheTwoDocumentedFlags() {
        assertTrue(CliCommandSupport.isHelp("--help"));
        assertTrue(CliCommandSupport.isHelp("-h"));
        assertFalse(CliCommandSupport.isHelp("help"));
        assertFalse(CliCommandSupport.isHelp("-H"));
    }
}
