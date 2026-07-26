package com.minos.mcp;

import com.minos.application.MinosApplication;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

import java.util.List;
import java.util.Objects;

/** Builds the current MCP catalogue on one shared MinosApplication instance. */
public final class MinosMcpApplicationTools {

    private MinosMcpApplicationTools() {
    }

    public static List<SyncToolSpecification> specifications(MinosApplication application) {
        MinosApplication app = Objects.requireNonNull(application, "application");
        return new MinosMcpTools(new MinosApplicationMcpBackend(app)).specifications();
    }
}
