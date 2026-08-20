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

    /**
     * Translates a UTF-8 byte budget into the JSON Schema {@code maxLength} that advertises it.
     *
     * <p>The two units are not the same thing, and conflating them made the published schema say
     * something MINOS does not mean. JSON Schema defines {@code maxLength} as a count of
     * <em>characters</em>; every bound above is a count of <em>UTF-8 bytes</em>. For ASCII they
     * coincide, which is why the difference stayed invisible; for {@code "é"} (2 bytes) or an emoji
     * (4 bytes) they do not.</p>
     *
     * <p>No {@code maxLength} can express a byte budget exactly, so the one published is the
     * tightest character bound the budget <em>implies</em>: a character is at least one UTF-8 byte,
     * therefore a value the server accepts can never exceed {@code maxUtf8Bytes} characters, and an
     * all-ASCII value of exactly that length is legal. That direction is the one that matters for a
     * client -- the schema never rejects a value MINOS would have accepted, so a compliant client
     * loses no capability. The converse does not hold: a schema-valid string of multi-byte
     * characters can still exceed the byte budget, which is precisely why {@code MinosMcpTools}
     * re-checks every argument in real UTF-8 bytes and why each property's {@code description}
     * states the byte budget explicitly instead of letting {@code maxLength} imply it.</p>
     *
     * <p>The alternative -- publishing {@code maxUtf8Bytes / 4}, the largest bound that could never
     * be exceeded by any input -- would make the schema sufficient as well as necessary, at the cost
     * of silently cutting the advertised capability to a quarter for the ASCII identifiers that
     * dominate real traffic. Under-advertising a limit is as dishonest as over-advertising it, and
     * it would deny valid requests, so it was rejected.</p>
     */
    static int schemaMaxCharacters(int maxUtf8Bytes) {
        if (maxUtf8Bytes < 1) {
            throw new IllegalArgumentException("maxUtf8Bytes must be greater than zero");
        }
        return maxUtf8Bytes;
    }
}
