package io.github.fturleque.minos.adapter.scip;

import org.scip_code.scip.SymbolInformation;

/**
 * Représentation interne d'un symbole SCIP avant normalisation MINOS.
 *
 * <p>Ce type appartient strictement à l'adaptateur. Il permet de préserver les
 * informations du protocole sans faire fuiter les identifiants SCIP dans le domaine.</p>
 */
record ScipSymbolFact(
        String rawSymbol,
        String displayName,
        SymbolInformation.Kind kind,
        String signature,
        String enclosingRawSymbol,
        String relativePath,
        String language,
        boolean external) {

    ScipSymbolFact {
        if (rawSymbol == null || rawSymbol.isBlank()) {
            throw new IllegalArgumentException("rawSymbol must not be blank");
        }
        displayName = displayName == null ? "" : displayName;
        kind = kind == null ? SymbolInformation.Kind.UnspecifiedKind : kind;
        signature = signature == null ? "" : signature;
        enclosingRawSymbol = enclosingRawSymbol == null ? "" : enclosingRawSymbol;
        relativePath = relativePath == null ? "" : relativePath;
        language = language == null ? "" : language;
    }
}
