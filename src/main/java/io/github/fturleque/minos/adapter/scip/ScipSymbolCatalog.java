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

    private static final String LOCAL_SYMBOL_PREFIX = "local ";

    private final Map<String, ScipSymbolFact> factsByCatalogKey;

    private ScipSymbolCatalog(Map<String, ScipSymbolFact> factsByCatalogKey) {
        this.factsByCatalogKey = Collections.unmodifiableMap(new LinkedHashMap<>(factsByCatalogKey));
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
                facts.put(key(document.getRelativePath(), fact.rawSymbol()), fact);
            }
        }

        for (SymbolInformation information : index.getExternalSymbolsList()) {
            ScipSymbolFact fact = toFact(information, "", "", true);
            facts.putIfAbsent(key("", fact.rawSymbol()), fact);
        }

        return new ScipSymbolCatalog(facts);
    }

    Optional<ScipSymbolFact> find(String relativePath, String rawSymbol) {
        return Optional.ofNullable(factsByCatalogKey.get(key(relativePath, rawSymbol)));
    }

    int size() {
        return factsByCatalogKey.size();
    }

    Map<String, ScipSymbolFact> asMap() {
        return factsByCatalogKey;
    }

    /**
     * SCIP local symbols are scoped to one document and their identifiers may be
     * reused in every document. Global symbols remain keyed by their provider id.
     */
    static String key(String relativePath, String rawSymbol) {
        if (rawSymbol != null && rawSymbol.startsWith(LOCAL_SYMBOL_PREFIX)) {
            return (relativePath == null ? "" : relativePath) + "\u001F" + rawSymbol;
        }
        return rawSymbol;
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
