package com.minos.program.analysis;

/** Hard construction budget propagated to program-graph providers and composition. */
public record ProgramGraphBudget(int maxNodes, int maxEdges) {
    public ProgramGraphBudget {
        if (maxNodes < 1 || maxEdges < 1) {
            throw new IllegalArgumentException("program graph budgets must be positive");
        }
    }
}
