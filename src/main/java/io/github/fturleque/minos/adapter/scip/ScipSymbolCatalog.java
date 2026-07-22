package io.github.fturleque.minos.adapter.scip;

import org.scip_code.scip.Document;
import org.scip_code.scip.Index;
import org.scip_code.scip.SymbolInformation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Catalogue interne des identités SCIP rencontrées dans un index.
 */
final class ScipSymbolCatalog {

    private final Map<String, ScipSymbolFact> factsByRawSymbol;

    private ScipSymbolCatalog(Map<String, ScipSymbolFact> factsByRawSymbol) {
        this.factsByRawSymbol = Collections.unmodifiableMap(new LinkedHashMap<>(factsByRawSymbol));
    }

    static ScipSymbolCatalog from(Index index) {
        Map<String, ScipSymbolFact> facts = new LinkedHashMap<>();

        for (Document document : index.getDocumentsList()) {
            for (SymbolInformation information : document.getSymbolsList()) {
                ScipSymbolFact fact = toFact(
                        information,
                        document.getRelativePath(),
                        document.getLanguage(),
                        false
                );
                facts.put(fact.rawSymbol(), fact);
            }
        }

        for (SymbolInformation information : index.getExternalSymbolsList()) {
            ScipSymbolFact fact = toFact(information, "", "", true);
            facts.putIfAbsent(fact.rawSymbol(), fact);
        }

        return new ScipSymbolCatalog(facts);
    }

    Optional<ScipSymbolFact> find(String rawSymbol) {
        return Optional.ofNullable(factsByRawSymbol.get(rawSymbol));
    }

    int size() {
        return factsByRawSymbol.size();
    }

    Map<String, ScipSymbolFact> asMap() {
        return factsByRawSymbol;
    }

    private static ScipSymbolFact toFact(
            SymbolInformation information,
            String relativePath,
            String documentLanguage,
            boolean external) {
        String signature = information.hasSignatureDocumentation()
                ? information.getSignatureDocumentation().getText()
                : "";
        String signatureLanguage = information.hasSignatureDocumentation()
                ? information.getSignatureDocumentation().getLanguage()
                : "";
        String language = !documentLanguage.isBlank() ? documentLanguage : signatureLanguage;

        return new ScipSymbolFact(
                information.getSymbol(),
                information.getDisplayName(),
                information.getKind(),
                signature,
                information.getEnclosingSymbol(),
                relativePath,
                language,
                external
        );
    }
}
