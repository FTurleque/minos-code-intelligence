package io.github.fturleque.minos.adapter.scip;

import io.github.fturleque.minos.domain.OccurrenceRole;
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

    private static final String PROJECT_ID_PROPERTY = "minos.m0.projectId";
    private static final String PROVIDER_ID_PROPERTY = "minos.m0.providerId";
    private static final String PROVIDER_VERSION_PROPERTY = "minos.m0.providerVersion";
    private static final String INDEX_RUN_ID_PROPERTY = "minos.m0.indexRunId";
    private static final String DEFAULT_PROJECT_ID = "m0-real-index";
    private static final String DEFAULT_PROVIDER_ID = "scip-java";
    private static final String DEFAULT_PROVIDER_VERSION = "0.13.1";
    private static final String DEFAULT_INDEX_RUN_ID = "m0-real-index";

    private ScipRealIndexExperiment() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length < 1) {
            throw new IllegalArgumentException("Usage: ScipRealIndexExperiment <index.scip> [queries...]");
        }

        Index index = new ScipIndexReader().read(Path.of(arguments[0]));
        String projectId = System.getProperty(PROJECT_ID_PROPERTY, DEFAULT_PROJECT_ID);
        String providerId = System.getProperty(PROVIDER_ID_PROPERTY, DEFAULT_PROVIDER_ID);
        String providerVersion = System.getProperty(PROVIDER_VERSION_PROPERTY, DEFAULT_PROVIDER_VERSION);
        String indexRunId = System.getProperty(INDEX_RUN_ID_PROPERTY, DEFAULT_INDEX_RUN_ID);
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
                        projectId,
                        "main",
                        providerId,
                        providerVersion,
                        indexRunId,
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
        Arrays.stream(arguments).skip(1).forEach(query -> emitQuery(queries, projectId, query));
    }

    @SuppressWarnings("deprecation")
    private static void emitProviderMetrics(Index index) {
        Set<String> uniqueRawSymbolIds = new HashSet<>();
        Set<String> catalogKeys = new HashSet<>();
        Map<String, Integer> occurrenceOnlySymbols = new TreeMap<>();
        Map<String, Integer> positionEncodings = new TreeMap<>();
        Map<String, Integer> roleCombinations = new TreeMap<>();
        Map<String, Integer> definitionsByCatalogKey = new TreeMap<>();
        Map<String, String> rawSymbolsByCatalogKey = new TreeMap<>();
        ScipOccurrenceRoleMapper roleMapper = new ScipOccurrenceRoleMapper();
        int symbolEntries = 0;
        int occurrences = 0;
        int definitions = 0;
        int blankSymbolOccurrences = 0;
        int multiValuedRoleOccurrences = 0;
        int typedRanges = 0;
        int legacyRanges = 0;
        int relationships = 0;
        int mainDocuments = 0;
        int testDocuments = 0;

        for (Document document : index.getDocumentsList()) {
            positionEncodings.merge(document.getPositionEncoding().name(), 1, Integer::sum);
            String relativePath = document.getRelativePath().replace('\\', '/');
            if (isTestSource(relativePath)) {
                testDocuments++;
            } else if (isMainSource(relativePath)) {
                mainDocuments++;
            }
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
                if (occurrence.getSymbol().isBlank()) {
                    blankSymbolOccurrences++;
                }
                Set<OccurrenceRole> mappedRoles = roleMapper.map(occurrence);
                String roleCombination = mappedRoles.stream()
                        .map(Enum::name)
                        .sorted()
                        .collect(Collectors.joining("+"));
                roleCombinations.merge(roleCombination, 1, Integer::sum);
                if (mappedRoles.size() > 1) {
                    multiValuedRoleOccurrences++;
                }
                if ((occurrence.getSymbolRoles() & 1) != 0) {
                    definitions++;
                    String catalogKey = ScipSymbolCatalog.key(
                            document.getRelativePath(),
                            occurrence.getSymbol()
                    );
                    definitionsByCatalogKey.merge(catalogKey, 1, Integer::sum);
                    rawSymbolsByCatalogKey.putIfAbsent(catalogKey, occurrence.getSymbol());
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

        int implementationRelationships = 0;
        int referenceRelationships = 0;
        int cataloguedRelationshipTargets = 0;
        for (Document document : index.getDocumentsList()) {
            for (SymbolInformation symbol : document.getSymbolsList()) {
                for (Relationship relationship : symbol.getRelationshipsList()) {
                    if (relationship.getIsImplementation()) {
                        implementationRelationships++;
                    }
                    if (relationship.getIsReference()) {
                        referenceRelationships++;
                    }
                    if (catalogKeys.contains(ScipSymbolCatalog.key(
                            document.getRelativePath(),
                            relationship.getSymbol()
                    ))) {
                        cataloguedRelationshipTargets++;
                    }
                }
            }
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
        metric("providerMainDocuments", mainDocuments);
        metric("providerTestDocuments", testDocuments);
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
        metric("providerBlankSymbolOccurrences", blankSymbolOccurrences);
        metric("providerMultiValuedRoleOccurrences", multiValuedRoleOccurrences);
        roleCombinations.forEach((roles, count) ->
                line("ROLE_COMBINATION", roles, Integer.toString(count)));
        long multiDefinitionSymbols = definitionsByCatalogKey.values().stream()
                .filter(count -> count > 1)
                .count();
        int maxDefinitionsPerSymbol = definitionsByCatalogKey.values().stream()
                .mapToInt(Integer::intValue)
                .max()
                .orElse(0);
        metric("providerMultiDefinitionSymbolIds", multiDefinitionSymbols);
        metric("providerMaxDefinitionsPerSymbol", maxDefinitionsPerSymbol);
        definitionsByCatalogKey.entrySet().stream()
                .filter(entry -> entry.getValue() > 1)
                .forEach(entry -> line(
                        "MULTI_DEFINITION_SYMBOL",
                        rawSymbolsByCatalogKey.get(entry.getKey()),
                        Integer.toString(entry.getValue())
                ));
        metric("providerTypedRanges", typedRanges);
        metric("providerLegacyRanges", legacyRanges);
        metric("providerRelationships", relationships);
        metric("providerImplementationRelationships", implementationRelationships);
        metric("providerReferenceRelationships", referenceRelationships);
        metric("providerCataloguedRelationshipTargets", cataloguedRelationshipTargets);
        metric("providerUncataloguedRelationshipTargets", relationships - cataloguedRelationshipTargets);
        positionEncodings.forEach((encoding, count) ->
                metric("providerPositionEncoding." + encoding, count));
        metric("providerOccurrenceOnlySymbolIds", occurrenceOnlySymbols.size());
        metric("providerOccurrenceOnlyOccurrences", occurrenceOnlySymbols.values().stream()
                .mapToInt(Integer::intValue)
                .sum());
        occurrenceOnlySymbols.forEach((symbol, count) ->
                line("OCCURRENCE_ONLY_SYMBOL", symbol, Integer.toString(count)));
    }

    private static void emitQuery(SymbolQueryService queries, String projectId, String query) {
        var symbols = queries.findSymbol(projectId, query, 100);
        line("QUERY", query, Integer.toString(symbols.size()));
        for (Symbol symbol : symbols) {
            String providerIds = symbol.providerReferences().stream()
                    .map(ProviderReference::externalId)
                    .sorted()
                    .reduce((left, right) -> left + "," + right)
                    .orElse("");
            var usages = queries.findUsages(projectId, symbol.id(), 1000);
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

    private static boolean isSourceSet(String relativePath, String sourceSet) {
        String segment = "src/" + sourceSet + "/";
        return relativePath.startsWith(segment) || relativePath.contains("/" + segment);
    }

    private static boolean isTestSource(String relativePath) {
        return isSourceSet(relativePath, "test")
                || relativePath.startsWith("test/")
                || relativePath.contains("/test/");
    }

    private static boolean isMainSource(String relativePath) {
        return isSourceSet(relativePath, "main")
                || relativePath.startsWith("src/")
                || relativePath.contains("/src/");
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
