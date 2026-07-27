package com.minos.integration.nexus;

import java.util.List;
import java.util.Objects;

/** M20 additive NEXUS v2 contract: code-local retrieval signals, never global context selection. */
public final class NexusSemanticSignalContract {

    public static final String CONTRACT_VERSION = "2";
    public static final String PRODUCER = "MINOS";

    private NexusSemanticSignalContract() {
    }

    public record Signal(String type, double score, String nature) {
    }

    public record Candidate(
            String stableKey,
            String kind,
            String sourceId,
            String fileId,
            int startLine,
            int endLine,
            double localRankingScore,
            String rankingMode,
            List<Signal> signals
    ) {
        public Candidate {
            signals = List.copyOf(Objects.requireNonNull(signals, "signals"));
        }
    }

    public record Export(
            String contractVersion,
            String producer,
            String projectId,
            String snapshotId,
            String query,
            boolean semanticAvailable,
            List<Candidate> candidates,
            List<String> limitations,
            String responsibilityBoundary
    ) {
        public Export {
            candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
            limitations = List.copyOf(Objects.requireNonNull(limitations, "limitations"));
        }
    }
}
