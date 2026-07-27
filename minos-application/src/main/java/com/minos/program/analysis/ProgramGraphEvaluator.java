package com.minos.program.analysis;

import com.minos.program.ProgramEdgeKind;
import com.minos.program.ProgramGraph;
import com.minos.program.ProgramGraphEdge;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Precision/recall evaluator used by M19 controlled ground-truth fixtures. */
public final class ProgramGraphEvaluator {

    public Evaluation evaluate(ProgramGraph graph, ProgramEdgeKind capabilityKind, Set<EdgeTruth> expected) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(capabilityKind, "capabilityKind");
        expected = Set.copyOf(Objects.requireNonNull(expected, "expected"));

        Set<EdgeTruth> observed = graph.edges().stream()
                .filter(edge -> edge.kind() == capabilityKind)
                .map(ProgramGraphEvaluator::truth)
                .collect(Collectors.toUnmodifiableSet());
        long truePositive = observed.stream().filter(expected::contains).count();
        double precision = observed.isEmpty() ? (expected.isEmpty() ? 1.0 : 0.0) : (double) truePositive / observed.size();
        double recall = expected.isEmpty() ? 1.0 : (double) truePositive / expected.size();
        return new Evaluation(expected.size(), observed.size(), Math.toIntExact(truePositive), precision, recall);
    }

    private static EdgeTruth truth(ProgramGraphEdge edge) {
        return new EdgeTruth(edge.sourceNodeId(), edge.targetNodeId(), edge.kind());
    }

    public record EdgeTruth(String sourceNodeId, String targetNodeId, ProgramEdgeKind kind) {
        public EdgeTruth {
            if (sourceNodeId == null || sourceNodeId.isBlank()) throw new IllegalArgumentException("sourceNodeId must not be blank");
            if (targetNodeId == null || targetNodeId.isBlank()) throw new IllegalArgumentException("targetNodeId must not be blank");
            Objects.requireNonNull(kind, "kind");
        }
    }

    public record Evaluation(int expected, int observed, int truePositive, double precision, double recall) {
        public boolean perfect() {
            return Double.compare(precision, 1.0) == 0 && Double.compare(recall, 1.0) == 0;
        }
    }
}
