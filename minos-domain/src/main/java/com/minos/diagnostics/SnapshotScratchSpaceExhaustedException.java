package com.minos.diagnostics;

/**
 * A snapshot view that cannot be loaded because the scratch area where its payload is copied before decoding does
 * not hold the payload.
 *
 * <p>Its message is public: it starts with {@value #MESSAGE_PREFIX} and carries only the two byte counts and the
 * setting to change, never a path nor the name of a scratch file. The CLI and the MCP server render it as is, like
 * {@link SnapshotMemoryExhaustedException}. It is an {@link java.io.IOException}: the signatures of the read ports do
 * not change.</p>
 */
public final class SnapshotScratchSpaceExhaustedException extends java.io.IOException {

    /** First words of every message of this refusal. */
    public static final String MESSAGE_PREFIX = "insufficient scratch space to load this snapshot";
    /** Setting of the Docker query role that sizes the scratch area. */
    public static final String DOCKER_SETTING = "MINOS_MCP_SCRATCH_SIZE";

    private final long payloadBytes;
    private final long availableBytes;

    /**
     * @param payloadBytes   size of the payload to copy
     * @param availableBytes usable bytes left in the scratch area when the refusal was decided
     */
    public SnapshotScratchSpaceExhaustedException(long payloadBytes, long availableBytes) {
        super(MESSAGE_PREFIX + ": its payload needs " + Math.max(0L, payloadBytes)
                + " bytes and the scratch area has " + Math.max(0L, availableBytes)
                + " bytes available; free space under MINOS_HOME or enlarge the scratch area ("
                + DOCKER_SETTING + " in Docker)");
        this.payloadBytes = Math.max(0L, payloadBytes);
        this.availableBytes = Math.max(0L, availableBytes);
    }

    public long payloadBytes() {
        return payloadBytes;
    }

    public long availableBytes() {
        return availableBytes;
    }
}
