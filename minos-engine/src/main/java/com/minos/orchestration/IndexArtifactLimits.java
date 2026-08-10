package com.minos.orchestration;

/** Authoritative physical limits shared by provider execution, transport and ingestion. */
public final class IndexArtifactLimits {

    /** Maximum supported encoded SCIP artifact size across every MINOS boundary. */
    public static final long MAX_SCIP_ARTIFACT_BYTES = 512L * 1024L * 1024L;

    private IndexArtifactLimits() {
    }
}
