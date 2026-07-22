package io.github.fturleque.minos.adapter.scip;

import io.github.fturleque.minos.domain.ProviderReference;
import io.github.fturleque.minos.domain.Symbol;
import io.github.fturleque.minos.query.SymbolQueryService;
import io.github.fturleque.minos.store.InMemoryCodeKnowledgeStore;
import org.scip_code.scip.Document;
import org.scip_code.scip.Index;
import org.scip_code.scip.Occurrence;
import org.scip_code.scip.Relationship;
import org.scip_code.scip.SymbolInformation;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Harness exécutable minimal pour mesurer un vrai index SCIP pendant M0.
 *
 * <p>Ce n'est pas une CLI produit. La classe reste dans les sources de test et
 * expose des lignes TSV stables pour le rapport d'expérience.</p>
 */
public final class ScipRealIndexExperiment {

    private static final String PROJECT_ID = "fixture-java-simple";

    private ScipRealIndexExperiment() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length < 1) {
            throw new IllegalArgumentException("Usage: ScipRealIndexExperiment <index.scip> [queries...]");
        }

        Index index = new ScipIndexReader().read(Path.of(arguments[0]));
        emitProviderMetrics(index);

        InMemoryCodeKnowledgeStore store = new InMemoryCodeKnowledgeStore();
        Map<String, String> explicitFileIds = index.getDocumentsList().stream()
                .collect(Collectors.toMap(
                        Document::getRelativePath,
                        Document::getRelativePath,
                        (first, ignored) -> first,
                        LinkedHashMap::new
                ));
        ScipIngestionReport report = new ScipIngestionAdapter().ingest(
                index,
                new ScipIngestionRequest(
                        PROJECT_ID,
                        "main",
                        "scip-java",
                        "0.13.1",
                        "m0-a1",
                        explicitFileIds
                ),
                store
        );

        metric("catalogSymbols", report.catalogSymbolCount());
        metric("normalizedSymbols", report.normalizedSymbolCount());
        metric("skippedSymbols", report.skippedSymbolCount());
        metric("occurrences", report.occurrenceCount());
        metric("resolvedOccurrences", report.resolvedOccurrenceCount());
        metric("unresolvedOccurrences", report.unresolvedOccurrenceCount());
        metric("skippedOccurrences", report.skippedOccurrenceCount());
        metric("unresolvedOccurrenceRate", report.unresolvedOccurrenceRate());

        SymbolQueryService queries = new SymbolQueryService(store);
        Arrays.stream(arguments).skip(1).forEach(query -> emitQuery(queries, query));
    }

    @SuppressWarnings("deprecation")
    private static void emitProviderMetrics(Index index) {
        Set<String> uniqueRawSymbolIds = new HashSet<>();
        Set<String> catalogKeys = new HashSet<>();
        Map<String, Integer> occurrenceOnlySymbols = new TreeMap<>();
        int symbolEntries = 0;
        int occurrences = 0;
        int definitions = 0;
        int typedRanges = 0;
        int legacyRanges = 0;
        int relationships = 0;

        for (Document document : index.getDocumentsList()) {
            for (SymbolInformation symbol : document.getSymbolsList()) {
                symbolEntries++;
                uniqueRawSymbolIds.add(symbol.getSymbol());
                catalogKeys.add(ScipSymbolCatalog.key(document.getRelativePath(), symbol.getSymbol()));
                relationships += symbol.getRelationshipsCount();
                line("SYMBOL", document.getRelativePath(), symbol.getSymbol(),
                        symbol.getKind().name(), symbol.getDisplayName());
                for (Relationship relationship : symbol.getRelationshipsList()) {
                    line("RELATIONSHIP", symbol.getSymbol(), relationship.getSymbol(),
                            Boolean.toString(relationship.getIsImplementation()),
                            Boolean.toString(relationship.getIsReference()));
                }
            }

            for (Occurrence occurrence : document.getOccurrencesList()) {
                occurrences++;
                if ((occurrence.getSymbolRoles() & 1) != 0) {
                    definitions++;
                }
                if (occurrence.getTypedRangeCase() != Occurrence.TypedRangeCase.TYPEDRANGE_NOT_SET) {
                    typedRanges++;
                }
                if (occurrence.getRangeCount() > 0) {
                    legacyRanges++;
                }
            }

        }

        for (SymbolInformation symbol : index.getExternalSymbolsList()) {
            uniqueRawSymbolIds.add(symbol.getSymbol());
            catalogKeys.add(ScipSymbolCatalog.key("", symbol.getSymbol()));
        }

        // Recompute after all documents and external symbols have been catalogued.
        occurrenceOnlySymbols.clear();
        for (Document document : index.getDocumentsList()) {
            for (Occurrence occurrence : document.getOccurrencesList()) {
                String catalogKey = ScipSymbolCatalog.key(
                        document.getRelativePath(),
                        occurrence.getSymbol()
                );
                if (!occurrence.getSymbol().isBlank() && !catalogKeys.contains(catalogKey)) {
                    occurrenceOnlySymbols.merge(occurrence.getSymbol(), 1, Integer::sum);
                }
            }
        }

        metric("documents", index.getDocumentsCount());
        metric("providerSymbolEntries", symbolEntries);
        metric("providerExternalSymbolEntries", index.getExternalSymbolsCount());
        metric("providerUniqueRawSymbolIds", uniqueRawSymbolIds.size());
        metric("providerCatalogSymbolFacts", catalogKeys.size());
        metric("providerReusedRawSymbolIds", symbolEntries
                + index.getExternalSymbolsCount() - uniqueRawSymbolIds.size());
        metric("providerDuplicateCatalogEntries", symbolEntries
                + index.getExternalSymbolsCount() - catalogKeys.size());
        metric("providerOccurrences", occurrences);
        metric("providerDefinitions", definitions);
        metric("providerTypedRanges", typedRanges);
        metric("providerLegacyRanges", legacyRanges);
        metric("providerRelationships", relationships);
        metric("providerOccurrenceOnlySymbolIds", occurrenceOnlySymbols.size());
        metric("providerOccurrenceOnlyOccurrences", occurrenceOnlySymbols.values().stream()
                .mapToInt(Integer::intValue)
                .sum());
        occurrenceOnlySymbols.forEach((symbol, count) ->
                line("OCCURRENCE_ONLY_SYMBOL", symbol, Integer.toString(count)));
    }

    private static void emitQuery(SymbolQueryService queries, String query) {
        var symbols = queries.findSymbol(PROJECT_ID, query, 100);
        line("QUERY", query, Integer.toString(symbols.size()));
        for (Symbol symbol : symbols) {
            String providerIds = symbol.providerReferences().stream()
                    .map(ProviderReference::externalId)
                    .sorted()
                    .reduce((left, right) -> left + "," + right)
                    .orElse("");
            var usages = queries.findUsages(PROJECT_ID, symbol.id(), 1000);
            line("QUERY_RESULT", query, symbol.name(), symbol.kind().name(),
                    symbol.identityQuality().name(), Integer.toString(usages.size()), providerIds);
            usages.forEach(usage -> line(
                    "QUERY_USAGE",
                    query,
                    symbol.name(),
                    usage.location().fileId(),
                    Integer.toString(usage.location().startLine()),
                    Integer.toString(usage.location().startColumn()),
                    usage.roles().stream().map(Enum::name).sorted().collect(Collectors.joining(","))
            ));
        }
    }

    private static void metric(String name, Object value) {
        line("METRIC", name, value.toString());
    }

    private static void line(String... values) {
        System.out.println(Arrays.stream(values)
                .map(ScipRealIndexExperiment::sanitize)
                .reduce((left, right) -> left + "\t" + right)
                .orElse(""));
    }

    private static String sanitize(String value) {
        return value == null
                ? ""
                : value.replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n");
    }
}
