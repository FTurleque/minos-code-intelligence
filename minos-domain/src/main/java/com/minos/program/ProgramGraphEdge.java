package com.minos.program;

import com.minos.domain.Evidence;
import com.minos.domain.InformationNature;
import com.minos.domain.Origin;
import com.minos.domain.ProbabilityInvariant;

import java.util.List;
import java.util.Objects;

/** Provider-independent directed edge in a reconstructible program graph. */
public record ProgramGraphEdge(
        String id,
        String projectId,
        String sourceNodeId,
        String targetNodeId,
        ProgramEdgeKind kind,
        InformationNature nature,
        Double confidence,
        Origin origin,
        List<Evidence> evidence
) {
    public ProgramGraphEdge {
        requireText(id, "id");
        requireText(projectId, "projectId");
        requireText(sourceNodeId, "sourceNodeId");
        requireText(targetNodeId, "targetNodeId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(nature, "nature");
        Objects.requireNonNull(origin, "origin");
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        ProbabilityInvariant.requireOptional(confidence, "confidence");
        if (nature != InformationNature.FACTUAL && confidence == null) {
            throw new IllegalArgumentException("derived or heuristic edge requires confidence");
        }
        if (nature != InformationNature.FACTUAL && evidence.isEmpty()) {
            throw new IllegalArgumentException("derived or heuristic edge requires evidence");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
