package com.minos.program;

import com.minos.domain.Evidence;
import com.minos.domain.InformationNature;
import com.minos.domain.Origin;
import com.minos.domain.SymbolLocation;

import java.util.List;
import java.util.Objects;

/** Provider-independent node in a reconstructible program graph. */
public record ProgramGraphNode(
        String id,
        String projectId,
        String symbolId,
        ProgramNodeKind kind,
        String label,
        SymbolLocation location,
        InformationNature nature,
        Double confidence,
        Origin origin,
        List<Evidence> evidence
) {
    public ProgramGraphNode {
        requireText(id, "id");
        requireText(projectId, "projectId");
        Objects.requireNonNull(kind, "kind");
        requireText(label, "label");
        Objects.requireNonNull(nature, "nature");
        Objects.requireNonNull(origin, "origin");
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        if (confidence != null && (confidence < 0.0 || confidence > 1.0)) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        if (nature != InformationNature.FACTUAL && confidence == null) {
            throw new IllegalArgumentException("derived or heuristic node requires confidence");
        }
        if (nature != InformationNature.FACTUAL && evidence.isEmpty()) {
            throw new IllegalArgumentException("derived or heuristic node requires evidence");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
