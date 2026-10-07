package com.minos.mcp;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MINOS-AUD-C01 : l'opérateur retrouve l'outil, la classe de l'exception et la classe de sa cause racine dans le
 * journal ; jamais le message de l'exception ni la valeur d'un argument du client.
 */
class MinosMcpFailureLoggingTest {

    @Test
    void anOpaqueFailureLogsTheToolAndBothClassesButNeitherTheMessageNorTheArguments() throws Exception {
        MinosMcpBackend backend = failingBackend(new IllegalStateException("outer wrapper",
                new IOException("jdbc:postgresql://db/minos password=super-secret /home/operator/minos",
                        new java.sql.SQLException("root cause secret=hunter2"))));
        MinosMcpTools tools = new MinosMcpTools(backend);

        List<LogRecord> records = capture(() -> {
            var result = McpToolCalls.call(tools, "minos_index_status", Map.of("project", "client-supplied-name"));
            assertEquals("error: MINOS tool execution failed", McpToolCalls.text(result));
        });

        LogRecord entry = records.stream()
                .filter(record -> record.getLevel() == Level.WARNING && record.getMessage().contains("MCP tool execution failed"))
                .findFirst().orElseThrow();
        String message = entry.getMessage();
        assertTrue(message.contains("tool=minos_index_status"), message);
        assertTrue(message.contains(IllegalStateException.class.getName()), message);
        assertTrue(message.contains("rootCause=" + java.sql.SQLException.class.getName()), message);
        for (String forbidden : new String[]{"outer wrapper", "super-secret", "hunter2", "jdbc:", "/home/operator",
                "client-supplied-name"}) {
            assertFalse(message.contains(forbidden), "the journal must not contain: " + forbidden);
        }
    }

    @Test
    void aFailureWithoutACauseNamesItselfAsItsRoot() throws Exception {
        MinosMcpTools tools = new MinosMcpTools(failingBackend(new IOException("disk /secret/path")));

        List<LogRecord> records = capture(() -> McpToolCalls.call(tools, "minos_index_status", Map.of("project", "p")));

        String message = records.stream().filter(record -> record.getMessage().contains("MCP tool execution failed"))
                .findFirst().orElseThrow().getMessage();
        assertTrue(message.contains("rootCause=" + IOException.class.getName()), message);
        assertFalse(message.contains("/secret/path"), message);
    }

    private static List<LogRecord> capture(Action action) throws Exception {
        Logger logger = Logger.getLogger(MinosMcpTools.class.getName());
        List<LogRecord> records = new ArrayList<>();
        Handler handler = new Handler() {
            @Override public void publish(LogRecord record) { records.add(record); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        logger.addHandler(handler);
        logger.setLevel(Level.ALL);
        try {
            action.run();
        } finally {
            logger.removeHandler(handler);
        }
        return records;
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

    @FunctionalInterface
    private interface Action {
        void run() throws Exception;
    }
}
