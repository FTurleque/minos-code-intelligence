package com.minos.adapter.scip;

import com.minos.domain.OccurrenceRole;
import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.ProviderReference;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.ResolvedSymbolReference;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolLocation;
import com.minos.domain.SymbolOccurrence;
import com.minos.domain.SymbolReference;
import com.minos.domain.UnresolvedSymbolReference;
import com.minos.store.CodeKnowledgeStore;
import org.scip_code.scip.Document;
import org.scip_code.scip.Index;
import org.scip_code.scip.Occurrence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Baseline M0 d'ingestion d'un index SCIP vers les contrats MINOS.
 *
 * <p>L'adaptateur travaille en deux passes : il normalise d'abord les symboles
 * connus, puis transforme les occurrences en références résolues ou non résolues.</p>
 */
final class ScipIngestionAdapter {

    private final ScipRangeMapper rangeMapper = new ScipRangeMapper();
    private final ScipOccurrenceRoleMapper roleMapper = new ScipOccurrenceRoleMapper();
    private final ScipSymbolNormalizer symbolNormalizer = new ScipSymbolNormalizer();

    ScipIngestionReport ingest(
            Index index,
            ScipIngestionRequest request,
            CodeKnowledgeStore store) {
        Objects.requireNonNull(index, "index");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(store, "store");

        ScipSymbolCatalog catalog = ScipSymbolCatalog.from(index);
        Map<String, SymbolLocation> definitionLocations = new HashMap<>();
        Set<String> generatedSymbols = new HashSet<>();

        collectDefinitionMetadata(index, request, definitionLocations, generatedSymbols);

        Map<String, Symbol> normalizedByCatalogKey = new LinkedHashMap<>();
        int skippedSymbols = 0;

        for (Map.Entry<String, ScipSymbolFact> entry : catalog.asMap().entrySet()) {
            String catalogKey = entry.getKey();
            ScipSymbolFact fact = entry.getValue();
            String fileId = fact.relativePath().isBlank()
                    ? null
                    : fileIdFor(request, fact.relativePath());
            SymbolLocation declarationLocation = definitionLocations.get(catalogKey);

            Optional<Symbol> normalized = symbolNormalizer.normalize(
                    fact,
                    request.projectId(),
                    request.moduleId(),
                    fileId,
                    declarationLocation,
                    request.providerId(),
                    request.providerVersion(),
                    request.indexRunId(),
                    generatedSymbols.contains(catalogKey)
            );

            if (normalized.isPresent()) {
                normalizedByCatalogKey.put(catalogKey, normalized.orElseThrow());
            } else {
                skippedSymbols++;
            }
        }

        store.putSymbols(normalizedByCatalogKey.values());

        List<SymbolOccurrence> normalizedOccurrences = new ArrayList<>();
        int resolvedOccurrences = 0;
        int unresolvedOccurrences = 0;
        int skippedOccurrences = 0;

        for (Document document : index.getDocumentsList()) {
            String fileId = fileIdFor(request, document.getRelativePath());

            for (Occurrence occurrence : document.getOccurrencesList()) {
                if (occurrence.getSymbol().isBlank()) {
                    skippedOccurrences++;
                    continue;
                }

                Optional<SymbolLocation> location = rangeMapper.map(
                        fileId,
                        occurrence,
                        document.getPositionEncoding()
                );
                if (location.isEmpty()) {
                    skippedOccurrences++;
                    continue;
                }

                Set<OccurrenceRole> roles = roleMapper.map(occurrence);
                String catalogKey = ScipSymbolCatalog.key(
                        document.getRelativePath(),
                        occurrence.getSymbol()
                );
                Symbol resolved = normalizedByCatalogKey.get(catalogKey);
                SymbolReference symbolReference;
                ResolutionStatus status;

                if (resolved != null) {
                    symbolReference = new ResolvedSymbolReference(resolved.id());
                    status = ResolutionStatus.RESOLVED;
                    resolvedOccurrences++;
                } else {
                    ScipSymbolFact knownFact = catalog.find(
                            document.getRelativePath(),
                            occurrence.getSymbol()
                    ).orElse(null);
                    symbolReference = new UnresolvedSymbolReference(
                            knownFact == null ? null : blankToNull(knownFact.displayName()),
                            null,
                            blankToNull(document.getLanguage()),
                            "SCIP occurrence has no normalized MINOS symbol",
                            Set.of(new ProviderReference(request.providerId(), occurrence.getSymbol()))
                    );
                    status = ResolutionStatus.UNRESOLVED;
                    unresolvedOccurrences++;
                }

                SymbolLocation normalizedLocation = location.orElseThrow();
                normalizedOccurrences.add(new SymbolOccurrence(
                        occurrenceId(request.projectId(), fileId, normalizedLocation, occurrence.getSymbol()),
                        request.projectId(),
                        symbolReference,
                        normalizedLocation,
                        roles,
                        status,
                        origin(request),
                        Set.of(new ProviderReference(request.providerId(), occurrence.getSymbol()))
                ));
            }
        }

        store.putOccurrences(normalizedOccurrences);

        return new ScipIngestionReport(
                catalog.size(),
                normalizedByCatalogKey.size(),
                skippedSymbols,
                normalizedOccurrences.size(),
                resolvedOccurrences,
                unresolvedOccurrences,
                skippedOccurrences
        );
    }

    private void collectDefinitionMetadata(
            Index index,
            ScipIngestionRequest request,
            Map<String, SymbolLocation> definitionLocations,
            Set<String> generatedSymbols) {
        for (Document document : index.getDocumentsList()) {
            String fileId = fileIdFor(request, document.getRelativePath());
            for (Occurrence occurrence : document.getOccurrencesList()) {
                if (occurrence.getSymbol().isBlank()) {
                    continue;
                }

                Set<OccurrenceRole> roles = roleMapper.map(occurrence);
                String catalogKey = ScipSymbolCatalog.key(
                        document.getRelativePath(),
                        occurrence.getSymbol()
                );
                if (roles.contains(OccurrenceRole.GENERATED)) {
                    generatedSymbols.add(catalogKey);
                }
                if (!roles.contains(OccurrenceRole.DEFINITION)
                        && !roles.contains(OccurrenceRole.FORWARD_DEFINITION)) {
                    continue;
                }

                rangeMapper.map(fileId, occurrence, document.getPositionEncoding())
                        .ifPresent(location -> definitionLocations.putIfAbsent(
                                catalogKey,
                                location
                        ));
            }
        }
    }

    private String fileIdFor(ScipIngestionRequest request, String relativePath) {
        String explicit = request.explicitFileId(relativePath);
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        return "file:" + sha256(request.projectId() + "\u001F" + relativePath);
    }

    private String occurrenceId(
            String projectId,
            String fileId,
            SymbolLocation location,
            String rawSymbol) {
        String material = String.join("\u001F",
                projectId,
                fileId,
                Integer.toString(location.startLine()),
                Integer.toString(location.startColumn()),
                Integer.toString(location.endLine()),
                Integer.toString(location.endColumn()),
                rawSymbol
        );
        return "occ:" + sha256(material);
    }

    private Origin origin(ScipIngestionRequest request) {
        return new Origin(
                request.providerId(),
                "SCIP_INDEXER",
                request.providerVersion(),
                request.indexRunId(),
                OriginType.SCIP
        );
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
