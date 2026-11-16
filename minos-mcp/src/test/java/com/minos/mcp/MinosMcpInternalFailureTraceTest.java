package com.minos.mcp;

import com.minos.application.diagnostics.InternalFailureReporter;
import com.minos.io.InternalFailureJournal;
import com.minos.testsupport.LogCapture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The operator can tie an opaque MCP error to its stderr line and, when enabled, to a private journal entry. */
class MinosMcpInternalFailureTraceTest {

    private static final Pattern RESPONSE_REFERENCE = Pattern.compile("\\(ref ([0-9a-f]{8})\\)$");
    private static final Map<String, String> ENABLED = Map.of(InternalFailureJournal.ENVIRONMENT_VARIABLE, "enabled");

    @TempDir
    Path temp;

    @Test
    void theResponseAndTheStderrLineShareTheReferenceAndTheLineNamesTheOriginInMinosCode() {
        MinosMcpTools tools = new MinosMcpTools(failingBackend(
                new IllegalStateException("wrapper", new NullPointerException("inner"))));

        try (LogCapture log = new LogCapture(MinosMcpTools.class)) {
            String text = McpToolCalls.text(McpToolCalls.call(tools, "minos_index_status", Map.of("project", "p")));

            String reference = referenceOf(text);
            assertTrue(text.startsWith("error: MINOS tool execution failed"), text);
            assertEquals(1, log.warnings().size(), log.warnings().toString());
            String line = log.warnings().get(0);
            assertTrue(line.contains("tool=minos_index_status"), line);
            assertTrue(line.contains("type=" + IllegalStateException.class.getName()), line);
            assertTrue(line.contains("rootCause=" + NullPointerException.class.getName()), line);
            assertTrue(line.contains("at=com.minos.mcp.MinosMcpInternalFailureTraceTest."), line);
            assertTrue(line.endsWith("ref=" + reference + ")"), line);
        }
    }

    @Test
    void aFailureWithoutAnyMinosFrameReportsAnUnknownOriginAndKeepsTheReference() {
        Exception failure = new IllegalStateException("boom");
        failure.setStackTrace(new StackTraceElement[]{new StackTraceElement("java.util.ArrayList", "get",
                "ArrayList.java", 427)});
        MinosMcpTools tools = new MinosMcpTools(failingBackend(failure));

        try (LogCapture log = new LogCapture(MinosMcpTools.class)) {
            String text = McpToolCalls.text(McpToolCalls.call(tools, "minos_index_status", Map.of("project", "p")));

            assertTrue(log.warnings().get(0).contains("at=unknown, ref=" + referenceOf(text)), log.warnings().get(0));
        }
    }

    @Test
    void aMessageCarryingAPathAndATokenReachesNeitherTheResponseNorTheLine() {
        MinosMcpTools tools = new MinosMcpTools(failingBackend(new IllegalStateException(
                "cannot open C:\\Users\\alice\\.minos\\registry.json token=abcdef123456")));

        try (LogCapture log = new LogCapture(MinosMcpTools.class)) {
            String text = McpToolCalls.text(McpToolCalls.call(tools, "minos_index_status", Map.of("project", "p")));

            assertTrue(text.matches("error: MINOS tool execution failed \\(ref [0-9a-f]{8}\\)"), text);
            for (String forbidden : new String[]{"alice", "abcdef123456", "registry.json"}) {
                assertFalse(text.contains(forbidden), text);
                assertFalse(log.warnings().get(0).contains(forbidden), log.warnings().get(0));
            }
        }
    }

    @Test
    void stdoutStaysFreeOfAnythingButTheProtocolWhenATraceIsWritten() {
        MinosMcpTools tools = new MinosMcpTools(failingBackend(new IllegalStateException("boom")),
                new InternalFailureReporter("mcp", temp, ENABLED, new Properties()));
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true));
        try {
            McpToolCalls.call(tools, "minos_index_status", Map.of("project", "p"));
        } finally {
            System.setOut(original);
        }

        assertEquals("", captured.toString(), "stdout is reserved for the protocol");
    }

    @Test
    void anEnabledJournalKeepsTheStackUnderTheReferenceOfTheStderrLine() throws Exception {
        MinosMcpTools tools = new MinosMcpTools(
                failingBackend(new IllegalStateException("wrapper", new NullPointerException("limit must be positive"))),
                new InternalFailureReporter("mcp", temp, ENABLED, new Properties()));

        String text = McpToolCalls.text(McpToolCalls.call(tools, "minos_index_status", Map.of("project", "p")));

        String reference = referenceOf(text);
        List<Path> entries = entries();
        assertEquals(1, entries.size());
        assertTrue(entries.get(0).getFileName().toString().endsWith("-" + reference + ".txt"));
        String content = Files.readString(entries.get(0));
        assertTrue(content.contains("surface=mcp operation=minos_index_status ref=" + reference), content);
        assertTrue(content.contains("caused by java.lang.NullPointerException: limit must be positive"), content);
        assertTrue(content.contains("at com.minos.mcp.MinosMcpInternalFailureTraceTest."), content);
        assertFalse(content.contains(temp.toString()), "no absolute path in the entry");
        assertFalse(content.contains("project=p") || content.contains("\"p\""), "no argument value in the entry");
    }

    @Test
    void anUnavailableJournalChangesNeitherTheResponsesNorTheDirectoryAndIsReportedOnce() throws Exception {
        Files.writeString(temp.resolve("logs"), "a file where the journal directory should be");
        List<String> before = listing();
        MinosMcpTools tools = new MinosMcpTools(failingBackend(new IllegalStateException("boom")),
                new InternalFailureReporter("mcp", temp, ENABLED, new Properties()));

        try (LogCapture tracing = new LogCapture(MinosMcpTools.class);
             LogCapture journal = new LogCapture(InternalFailureJournal.class)) {
            String first = McpToolCalls.text(McpToolCalls.call(tools, "minos_index_status", Map.of("project", "p")));
            String second = McpToolCalls.text(McpToolCalls.call(tools, "minos_index_status", Map.of("project", "p")));

            McpToolCalls.assertGenericToolError(first);
            McpToolCalls.assertGenericToolError(second);
            assertEquals(2, tracing.warnings().size(), tracing.warnings().toString());
            assertEquals(1, journal.warnings().size(), journal.warnings().toString());
        }
        assertEquals(before, listing(), "the directory is left as it was found");
    }

    private static String referenceOf(String text) {
        Matcher matcher = RESPONSE_REFERENCE.matcher(text);
        assertTrue(matcher.find(), text);
        return matcher.group(1);
    }

    private List<Path> entries() throws Exception {
        Path directory = temp.resolve("logs").resolve("internal-failures");
        if (!Files.isDirectory(directory)) return List.of();
        try (Stream<Path> stream = Files.list(directory)) {
            return stream.toList();
        }
    }

    private List<String> listing() throws Exception {
        try (Stream<Path> stream = Files.walk(temp)) {
            return stream.map(path -> temp.relativize(path).toString()).sorted().toList();
        }
    }

    private static MinosMcpBackend failingBackend(Throwable failure) {
        return (MinosMcpBackend) Proxy.newProxyInstance(
                MinosMcpBackend.class.getClassLoader(),
                new Class<?>[]{MinosMcpBackend.class},
                (proxy, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> "failing-mcp-backend";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == arguments[0];
                            default -> null;
                        };
                    }
                    throw failure;
                });
    }
}
