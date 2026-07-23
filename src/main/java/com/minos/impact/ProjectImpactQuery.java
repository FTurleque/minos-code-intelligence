package com.minos.impact;

import java.io.IOException;

/**
 * Façade publique fournisseur-indépendante d'analyse d'impact M8.
 */
public interface ProjectImpactQuery {

    ImpactAnalysisReport analyzeImpact(String projectIdentifier, ImpactAnalysisRequest request) throws IOException;

    default ImpactAnalysisReport analyzeImpact(String projectIdentifier, String symbolId) throws IOException {
        return analyzeImpact(projectIdentifier, ImpactAnalysisRequest.defaults(symbolId));
    }
}
