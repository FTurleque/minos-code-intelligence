package com.minos.mcp;

import com.minos.application.ProjectResolver;

/**
 * Centralized semantic maximum lengths for MCP tool string arguments.
 *
 * <p>Every bound here is expressed in UTF-8 bytes, the unit MINOS's deeper domain validation
 * already uses (see {@link ProjectResolver#MAX_REFERENCE_UTF8_BYTES} and its analogues in
 * {@code minos-domain}), and is applied at two independent layers: the JSON Schema {@code
 * maxLength} advertised to MCP clients, and a server-side check in {@link MinosMcpTools} that a
 * malicious or non-compliant client cannot bypass by ignoring the schema. Values are chosen from
 * the real shape of each argument, not picked arbitrarily: identifiers and short structured tokens
 * get tight bounds, free text gets more room, and {@code project} mirrors the existing {@link
 * ProjectResolver} boundary exactly instead of inventing a second, inconsistent number for the
 * same concept.</p>
 */
final class McpArgumentBounds {

    private McpArgumentBounds() {
    }

    /** Tiny enum-like tokens, e.g. {@code format}. */
    static final int SMALL_TOKEN_MAX_UTF8_BYTES = 64;

    /** Module names/paths (Maven coordinates, Gradle project paths, nested module segments). */
    static final int MODULE_NAME_MAX_UTF8_BYTES = 512;

    /** Symbol kind labels (e.g. {@code CLASS}, {@code METHOD}). */
    static final int KIND_MAX_UTF8_BYTES = 128;

    /** Fully qualified names, including nested/generic signatures. */
    static final int QUALIFIED_NAME_MAX_UTF8_BYTES = 2048;

    /** Project identifiers: mirrors the resolution boundary every reference eventually crosses. */
    static final int PROJECT_REFERENCE_MAX_UTF8_BYTES = ProjectResolver.MAX_REFERENCE_UTF8_BYTES;

    /** Free-text search/natural-language queries. */
    static final int QUERY_MAX_UTF8_BYTES = 4096;

    /** SCIP-style structured symbol/node identifiers ({@code symbolId}, {@code sourceNodeId}). */
    static final int SCIP_SYMBOL_ID_MAX_UTF8_BYTES = 4096;

    /** Opaque session/workspace handles, typically UUIDs. */
    static final int HANDLE_MAX_UTF8_BYTES = 256;
}
