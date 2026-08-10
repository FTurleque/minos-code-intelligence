package com.minos.mcp;

import com.minos.application.MinosApplication;
import com.minos.application.MinosHome;
import com.minos.runtime.MinosVersion;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;

/** Serveur MCP local MINOS en transport STDIO. */
public final class MinosMcpServer {

    public static final String SERVER_NAME = "minos-code-intelligence";
    public static final String SERVER_VERSION = MinosVersion.current();
    public static final int MAX_INBOUND_MESSAGE_BYTES = 1024 * 1024;
    private static final System.Logger LOGGER = System.getLogger(MinosMcpServer.class.getName());

    private MinosMcpServer() { }

    public static void main(String[] arguments) {
        try {
            run(resolveHome(System.getenv(), System.getProperties()));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            LOGGER.log(System.Logger.Level.ERROR,
                    "MINOS MCP bootstrap failed (type=" + exception.getClass().getName() + ")");
            System.exit(1);
        }
    }

    /** Compatibility entry point for a resolved MINOS home; this method owns the opened application. */
    public static void run(Path home) throws Exception {
        try (MinosApplication application = MinosApplication.open(Objects.requireNonNull(home, "home"))) {
            run(application);
        }
    }

    /** Launches one MCP STDIO session on an already-composed application without taking its ownership. */
    public static void run(MinosApplication application) throws Exception {
        MinosApplication app = Objects.requireNonNull(application, "application");
        EofAwareInputStream stdin = new EofAwareInputStream(System.in);
        StdioServerTransportProvider transport = new StdioServerTransportProvider(
                McpJsonDefaults.getMapper(), stdin, System.out);
        McpSyncServer server = McpServer.sync(transport)
                .serverInfo(SERVER_NAME, SERVER_VERSION)
                .instructions("MINOS exposes read-only local code intelligence. Tool results are bounded JSON produced by the validated MINOS core.")
                .capabilities(ServerCapabilities.builder().tools(false).build())
                .tools(MinosMcpApplicationTools.specifications(app))
                .build();
        try {
            stdin.awaitEnd();
        } finally {
            server.close();
        }
    }

    static Path resolveHome(Map<String, String> environment, Properties properties) {
        return MinosHome.resolve(environment, properties);
    }

    /**
     * Exposes the real STDIO end-of-input signal and rejects a newline-delimited MCP JSON-RPC
     * message before it can grow beyond the server-wide byte cap. Per-tool DTOs retain tighter
     * semantic bounds where appropriate.
     */
    static final class EofAwareInputStream extends FilterInputStream {
        private final CountDownLatch ended = new CountDownLatch(1);
        private int currentMessageBytes;

        EofAwareInputStream(InputStream input) {
            super(Objects.requireNonNull(input, "input"));
        }

        @Override
        public int read() throws IOException {
            try {
                int value = super.read();
                if (value < 0) {
                    ended.countDown();
                    return value;
                }
                account(value);
                return value;
            } catch (IOException exception) {
                ended.countDown();
                throw exception;
            }
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            try {
                int read = super.read(buffer, offset, length);
                if (read < 0) {
                    ended.countDown();
                    return read;
                }
                for (int index = offset; index < offset + read; index++) account(buffer[index] & 0xff);
                return read;
            } catch (IOException exception) {
                ended.countDown();
                throw exception;
            }
        }

        private void account(int value) throws IOException {
            if (value == '\n') {
                currentMessageBytes = 0;
                return;
            }
            if (currentMessageBytes >= MAX_INBOUND_MESSAGE_BYTES) {
                throw new IOException("MCP inbound message exceeds byte limit: " + MAX_INBOUND_MESSAGE_BYTES);
            }
            currentMessageBytes++;
        }

        @Override
        public void close() throws IOException {
            ended.countDown();
            super.close();
        }

        void awaitEnd() throws InterruptedException { ended.await(); }
    }
}
