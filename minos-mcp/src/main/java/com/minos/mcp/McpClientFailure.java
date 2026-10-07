package com.minos.mcp;

/**
 * A failure whose cause is known and safe to tell the MCP client: a fixed message supplied by the backend, never built from client input,
 * a path or a secret. The tool mapping reports it as is, where any other internal failure stays generic. The
 * implementations extend the exception types the backend has always thrown, so its callers are unchanged.
 */
interface McpClientFailure {

    /** The fixed text a client may read. */
    String clientMessage();

    /** The opt-in team mode is not enabled for this installation. */
    final class TeamModeDisabled extends IllegalStateException implements McpClientFailure {
        private static final long serialVersionUID = 1L;

        TeamModeDisabled(String fixedMessage) {
            super(fixedMessage);
        }

        @Override
        public String clientMessage() {
            return getMessage();
        }
    }

    /** A team tool was called without a team token in the environment of the server process. */
    final class TeamTokenRequired extends SecurityException implements McpClientFailure {
        private static final long serialVersionUID = 1L;

        TeamTokenRequired(String fixedMessage) {
            super(fixedMessage);
        }

        @Override
        public String clientMessage() {
            return getMessage();
        }
    }
}
