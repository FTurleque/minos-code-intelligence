package com.minos.impact;

import com.minos.domain.Symbol;

import java.util.List;
import java.util.Objects;

/**
 * Symbole potentiellement impacté avec profondeur, confiance et chemin explicatif.
 */
public record ImpactedSymbol(
        Symbol symbol,
        ImpactLevel level,
        int depth,
        double confidence,
        List<ImpactPathStep> path,
        boolean testImpact
) {
    public ImpactedSymbol {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(level, "level");
        if (depth < 1) {
            throw new IllegalArgumentException("depth must be positive");
        }
        if ((depth == 1) != (level == ImpactLevel.DIRECT)) {
            throw new IllegalArgumentException("DIRECT requires depth 1 and INDIRECT requires depth > 1");
        }
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        path = List.copyOf(Objects.requireNonNull(path, "path"));
        if (path.size() != depth) {
            throw new IllegalArgumentException("path size must equal depth");
        }
        if (!path.getLast().impactedSymbolId().equals(symbol.id())) {
            throw new IllegalArgumentException("path must end at impacted symbol");
        }
    }
}
