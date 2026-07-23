package com.minos.query;

import com.minos.domain.CodeEntityRef;
import com.minos.domain.CodeEntityType;
import com.minos.domain.Evidence;
import com.minos.domain.EvidenceType;
import com.minos.domain.InformationNature;
import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.Relationship;
import com.minos.domain.RelationshipKind;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolKind;
import com.minos.domain.SymbolOccurrence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Dérive des relations {@link RelationshipKind#RELATED_TEST} explicables.
 *
 * <p>La relation est orientée du symbole de test vers le symbole de production.
 * Une référence ou un appel direct produit une dérivation. Une convention de
 * nommage peut produire une heuristique, tandis que la proximité de namespace
 * ne peut que renforcer un candidat déjà établi.</p>
 */
public final class RelatedTestDerivationService {

    private static final double TEST_LOCATION_WEIGHT = 0.10;
    private static final double PACKAGE_PROXIMITY_WEIGHT = 0.20;
    private static final double NAMING_CONVENTION_WEIGHT = 0.55;
    private static final double DIRECT_REFERENCE_WEIGHT = 0.65;
    private static final double DIRECT_CALL_WEIGHT = 0.80;

    private static final Set<SymbolKind> CONTAINER_KINDS = Set.of(
            SymbolKind.CLASS,
            SymbolKind.RECORD,
            SymbolKind.STRUCT,
            SymbolKind.TRAIT,
            SymbolKind.ENUM
    );
    private static final Pattern TEST_SUFFIX = Pattern.compile(
            "(?i)(?:tests?|spec(?:ification)?s?|it)$"
    );

    public List<Relationship> derive(
            Collection<Symbol> symbols,
            Collection<SymbolOccurrence> occurrences,
            Collection<Relationship> relationships
    ) {
        if (symbols == null || symbols.isEmpty()) {
            return List.of();
        }

        List<Symbol> orderedSymbols = symbols.stream()
                .filter(Objects::nonNull)
                .filter(symbol -> !symbol.external() && !symbol.generated())
                .sorted(Comparator.comparing(Symbol::projectId).thenComparing(Symbol::id))
                .toList();
        Map<ScopedSymbolId, Symbol> symbolsById = new HashMap<>();
        Map<ScopedFileId, List<Symbol>> symbolsByFile = new LinkedHashMap<>();
        for (Symbol symbol : orderedSymbols) {
            symbolsById.put(new ScopedSymbolId(symbol.projectId(), symbol.id()), symbol);
            if (symbol.fileId() != null) {
                symbolsByFile.computeIfAbsent(
                        new ScopedFileId(symbol.projectId(), symbol.fileId()),
                        ignored -> new ArrayList<>()
                ).add(symbol);
            }
        }

        Map<ScopedFileId, List<Symbol>> testAnchorsByFile = new LinkedHashMap<>();
        Map<ScopedFileId, Symbol> uniqueTestAnchorByFile = new LinkedHashMap<>();
        symbolsByFile.forEach((file, fileSymbols) -> {
            if (!isTestPath(file.fileId())) {
                return;
            }
            List<Symbol> anchors = testAnchors(fileSymbols);
            if (!anchors.isEmpty()) {
                testAnchorsByFile.put(file, anchors);
            }
            if (anchors.size() == 1) {
                uniqueTestAnchorByFile.put(file, anchors.getFirst());
            }
        });

        Map<RelatedTestKey, Candidate> candidates = new LinkedHashMap<>();
        addNamingCandidates(orderedSymbols, testAnchorsByFile, candidates);
        addOccurrenceCandidates(occurrences, symbolsById, uniqueTestAnchorByFile, candidates);
        addRelationshipCandidates(
                relationships,
                symbolsById,
                uniqueTestAnchorByFile,
                candidates
        );

        return candidates.values().stream()
                .peek(this::addContextEvidence)
                .map(this::toRelationship)
                .sorted(Comparator.comparing(Relationship::id))
                .toList();
    }

    private void addNamingCandidates(
            List<Symbol> symbols,
            Map<ScopedFileId, List<Symbol>> testAnchorsByFile,
            Map<RelatedTestKey, Candidate> candidates
    ) {
        Map<ScopedNormalizedName, List<Symbol>> productionByName = new LinkedHashMap<>();
        for (Symbol symbol : symbols) {
            if (!isTestSymbol(symbol)) {
                productionByName.computeIfAbsent(
                        new ScopedNormalizedName(
                                symbol.projectId(), normalizedName(symbol.name())),
                        ignored -> new ArrayList<>()
                ).add(symbol);
            }
        }
        for (Map.Entry<ScopedFileId, List<Symbol>> entry : testAnchorsByFile.entrySet()) {
            for (Symbol test : entry.getValue()) {
                Set<String> candidateNames = new LinkedHashSet<>();
                candidateNames.add(normalizedName(stripTestSuffix(test.name())));
                candidateNames.add(normalizedName(stripTestFileSuffix(fileStem(test.fileId()))));
                candidateNames.remove("");
                List<Symbol> matchingProduction = candidateNames.stream()
                        .flatMap(name -> productionByName.getOrDefault(
                                new ScopedNormalizedName(test.projectId(), name), List.of()
                        ).stream())
                        .distinct()
                        .sorted(Comparator.comparing(Symbol::id))
                        .toList();
                for (Symbol production : matchingProduction) {
                    if (test.id().equals(production.id())) {
                        continue;
                    }
                    Candidate candidate = candidate(candidates, test, production);
                    candidate.add(
                            EvidenceType.NAMING_CONVENTION,
                            "Test name or file stem matches production symbol " + production.name(),
                            ref(test),
                            ref(production),
                            test.location(),
                            NAMING_CONVENTION_WEIGHT
                    );
                }
            }
        }
    }

    private void addOccurrenceCandidates(
            Collection<SymbolOccurrence> occurrences,
            Map<ScopedSymbolId, Symbol> symbolsById,
            Map<ScopedFileId, Symbol> uniqueTestAnchorByFile,
            Map<RelatedTestKey, Candidate> candidates
    ) {
        if (occurrences == null) {
            return;
        }
        occurrences.stream()
                .filter(Objects::nonNull)
                .filter(SymbolOccurrence::isResolved)
                .filter(occurrence -> !occurrence.isDefinitionOccurrence())
                .sorted(Comparator.comparing(SymbolOccurrence::projectId)
                        .thenComparing(occurrence -> occurrence.location().fileId())
                        .thenComparingInt(occurrence -> occurrence.location().startLine())
                        .thenComparingInt(occurrence -> occurrence.location().startColumn())
                        .thenComparing(SymbolOccurrence::id))
                .forEach(occurrence -> {
                    Symbol test = uniqueTestAnchorByFile.get(new ScopedFileId(
                            occurrence.projectId(), occurrence.location().fileId()));
                    Symbol production = occurrence.resolvedSymbolId()
                            .map(id -> symbolsById.get(new ScopedSymbolId(occurrence.projectId(), id)))
                            .orElse(null);
                    if (test == null || production == null || isTestSymbol(production)
                            || test.id().equals(production.id())) {
                        return;
                    }
                    candidate(candidates, test, production).add(
                            EvidenceType.DIRECT_REFERENCE,
                            "Resolved reference from test file to " + production.name(),
                            ref(test),
                            ref(production),
                            occurrence.location(),
                            DIRECT_REFERENCE_WEIGHT
                    );
                });
    }

    private void addRelationshipCandidates(
            Collection<Relationship> relationships,
            Map<ScopedSymbolId, Symbol> symbolsById,
            Map<ScopedFileId, Symbol> uniqueTestAnchorByFile,
            Map<RelatedTestKey, Candidate> candidates
    ) {
        if (relationships == null) {
            return;
        }
        relationships.stream()
                .filter(Objects::nonNull)
                .filter(relationship -> relationship.nature() == InformationNature.FACTUAL)
                .filter(relationship -> relationship.target() != null)
                .filter(relationship -> relationship.kind() == RelationshipKind.CALLS
                        || relationship.kind() == RelationshipKind.REFERENCES)
                .sorted(Comparator.comparing(Relationship::projectId)
                        .thenComparing(Relationship::kind)
                        .thenComparing(Relationship::id))
                .forEach(relationship -> {
                    Symbol factualSource = symbolsById.get(new ScopedSymbolId(
                            relationship.projectId(), relationship.source().id()));
                    Symbol production = symbolsById.get(new ScopedSymbolId(
                            relationship.projectId(), relationship.target().id()));
                    if (factualSource == null || production == null
                            || !isTestSymbol(factualSource) || isTestSymbol(production)) {
                        return;
                    }
                    Symbol test = anchorFor(factualSource, uniqueTestAnchorByFile);
                    if (test == null || test.id().equals(production.id())) {
                        return;
                    }
                    boolean directCall = relationship.kind() == RelationshipKind.CALLS;
                    candidate(candidates, test, production).add(
                            directCall ? EvidenceType.DIRECT_CALL : EvidenceType.DIRECT_REFERENCE,
                            "Direct " + relationship.kind().name()
                                    + " provider fact from test symbol " + factualSource.name(),
                            relationship.source(),
                            relationship.target(),
                            relationship.location(),
                            directCall ? DIRECT_CALL_WEIGHT : DIRECT_REFERENCE_WEIGHT
                    );
                });
    }

    private void addContextEvidence(Candidate candidate) {
        candidate.add(
                EvidenceType.TEST_LOCATION,
                "Test symbol is declared in " + candidate.test.fileId(),
                ref(candidate.test),
                ref(candidate.production),
                candidate.test.location(),
                TEST_LOCATION_WEIGHT
        );
        if (sameNamespace(candidate.test, candidate.production)) {
            candidate.add(
                    EvidenceType.PACKAGE_PROXIMITY,
                    "Test and production symbols share namespace " + namespace(candidate.test),
                    ref(candidate.test),
                    ref(candidate.production),
                    candidate.test.location(),
                    PACKAGE_PROXIMITY_WEIGHT
            );
        }
    }

    private Relationship toRelationship(Candidate candidate) {
        boolean derived = candidate.has(EvidenceType.DIRECT_REFERENCE)
                || candidate.has(EvidenceType.DIRECT_CALL);
        Origin origin = new Origin(
                "minos",
                "RELATED_TEST_DERIVATION",
                "M5",
                candidate.test.origin().indexRunId(),
                OriginType.DERIVED_BY_MINOS
        );
        RelatedTestKey key = new RelatedTestKey(
                candidate.test.projectId(),
                candidate.test.id(),
                candidate.production.id()
        );
        return new Relationship(
                relationshipId(key),
                candidate.test.projectId(),
                ref(candidate.test),
                ref(candidate.production),
                null,
                RelationshipKind.RELATED_TEST,
                candidate.test.location(),
                derived ? ResolutionStatus.RESOLVED : ResolutionStatus.HEURISTIC,
                derived ? InformationNature.DERIVED : InformationNature.HEURISTIC,
                confidence(candidate.signalWeights),
                origin,
                candidate.evidence.stream()
                        .sorted(Comparator.comparing(Evidence::type)
                                .thenComparing(Evidence::description)
                                .thenComparing(evidence -> evidence.location() == null)
                                .thenComparing(evidence -> evidence.location() == null
                                        ? "" : evidence.location().fileId())
                                .thenComparingInt(evidence -> evidence.location() == null
                                        ? Integer.MAX_VALUE : evidence.location().startLine())
                                .thenComparingInt(evidence -> evidence.location() == null
                                        ? Integer.MAX_VALUE : evidence.location().startColumn()))
                        .toList()
        );
    }

    private static List<Symbol> testAnchors(List<Symbol> symbols) {
        List<Symbol> containers = symbols.stream()
                .filter(symbol -> CONTAINER_KINDS.contains(symbol.kind()))
                .sorted(Comparator.comparing(Symbol::id))
                .toList();
        if (!containers.isEmpty()) {
            return containers;
        }
        List<Symbol> functions = symbols.stream()
                .filter(symbol -> symbol.kind() == SymbolKind.FUNCTION)
                .sorted(Comparator.comparing(Symbol::id))
                .toList();
        if (!functions.isEmpty()) {
            return functions;
        }
        // Certains indexeurs SCIP TypeScript publient encore les fonctions avec
        // UnspecifiedKind. Les symboles sans display name (module et locaux) ont
        // déjà été écartés par la normalisation ; un unique OTHER reste donc un
        // ancrage prudent, tandis que plusieurs candidats restent ambigus.
        return symbols.stream()
                .filter(symbol -> symbol.kind() == SymbolKind.OTHER)
                .sorted(Comparator.comparing(Symbol::id))
                .toList();
    }

    private static Symbol anchorFor(
            Symbol factualSource,
            Map<ScopedFileId, Symbol> uniqueTestAnchorByFile
    ) {
        if (isAnchorKind(factualSource.kind())) {
            return factualSource;
        }
        if (factualSource.fileId() == null) {
            return null;
        }
        return uniqueTestAnchorByFile.get(new ScopedFileId(
                factualSource.projectId(), factualSource.fileId()));
    }

    private static boolean isAnchorKind(SymbolKind kind) {
        return CONTAINER_KINDS.contains(kind)
                || kind == SymbolKind.FUNCTION
                || kind == SymbolKind.OTHER;
    }

    private static boolean sameNamespace(Symbol first, Symbol second) {
        String firstNamespace = namespace(first);
        return !firstNamespace.isEmpty() && firstNamespace.equals(namespace(second));
    }

    private static String namespace(Symbol symbol) {
        String qualifiedName = symbol.qualifiedName();
        if (qualifiedName == null || qualifiedName.isBlank()) {
            return "";
        }
        int separator = Math.max(qualifiedName.lastIndexOf('.'), qualifiedName.lastIndexOf('/'));
        if (separator < 0) {
            return "";
        }
        String namespace = qualifiedName.substring(0, separator);
        if (isMemberKind(symbol.kind())) {
            int enclosingSeparator = Math.max(
                    namespace.lastIndexOf('.'), namespace.lastIndexOf('/'));
            if (enclosingSeparator >= 0) {
                namespace = namespace.substring(0, enclosingSeparator);
            }
        }
        return namespace;
    }

    private static boolean isMemberKind(SymbolKind kind) {
        return switch (kind) {
            case METHOD, CONSTRUCTOR, FIELD, PROPERTY, VARIABLE -> true;
            default -> false;
        };
    }

    private static String stripTestSuffix(String value) {
        return value == null ? "" : TEST_SUFFIX.matcher(value).replaceFirst("");
    }

    private static String stripTestFileSuffix(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceFirst("(?i)(?:[._-](?:tests?|spec(?:ification)?s?|it))$", "");
    }

    private static String fileStem(String fileId) {
        if (fileId == null) {
            return "";
        }
        String normalized = fileId.replace('\\', '/');
        String fileName = normalized.substring(normalized.lastIndexOf('/') + 1);
        int extension = fileName.lastIndexOf('.');
        return extension < 0 ? fileName : fileName.substring(0, extension);
    }

    private static String normalizedName(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static boolean isTestSymbol(Symbol symbol) {
        return symbol.fileId() != null && isTestPath(symbol.fileId());
    }

    static boolean isTestPath(String fileId) {
        if (fileId == null || fileId.isBlank()) {
            return false;
        }
        String normalized = "/" + fileId.replace('\\', '/').toLowerCase(Locale.ROOT);
        return normalized.contains("/src/test/")
                || normalized.contains("/test/")
                || normalized.contains("/tests/")
                || normalized.contains("/__tests__/")
                || normalized.matches(".*\\.(?:test|spec)\\.[^/]+$");
    }

    private static Candidate candidate(
            Map<RelatedTestKey, Candidate> candidates,
            Symbol test,
            Symbol production
    ) {
        RelatedTestKey key = new RelatedTestKey(test.projectId(), test.id(), production.id());
        return candidates.computeIfAbsent(key, ignored -> new Candidate(test, production));
    }

    private static CodeEntityRef ref(Symbol symbol) {
        return new CodeEntityRef(CodeEntityType.SYMBOL, symbol.id());
    }

    private static double confidence(Map<EvidenceType, Double> signalWeights) {
        double remainingUncertainty = 1.0;
        for (double weight : signalWeights.values()) {
            remainingUncertainty *= 1.0 - weight;
        }
        return Math.round((1.0 - remainingUncertainty) * 1_000.0) / 1_000.0;
    }

    private static String relationshipId(RelatedTestKey key) {
        String material = String.join("\u001F",
                key.projectId(), key.testSymbolId(), key.productionSymbolId(),
                RelationshipKind.RELATED_TEST.name());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "rel:" + HexFormat.of().formatHex(
                    digest.digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private record ScopedSymbolId(String projectId, String symbolId) {
    }

    private record ScopedFileId(String projectId, String fileId) {
    }

    private record ScopedNormalizedName(String projectId, String normalizedName) {
    }

    private record RelatedTestKey(
            String projectId,
            String testSymbolId,
            String productionSymbolId
    ) {
    }

    private static final class Candidate {
        private final Symbol test;
        private final Symbol production;
        private final List<Evidence> evidence = new ArrayList<>();
        private final Set<String> evidenceKeys = new LinkedHashSet<>();
        private final Map<EvidenceType, Double> signalWeights = new EnumMap<>(EvidenceType.class);

        private Candidate(Symbol test, Symbol production) {
            this.test = test;
            this.production = production;
        }

        private void add(
                EvidenceType type,
                String description,
                CodeEntityRef source,
                CodeEntityRef target,
                com.minos.domain.SymbolLocation location,
                double weight
        ) {
            String locationKey = location == null ? "" : location.fileId() + ":"
                    + location.startLine() + ":" + location.startColumn();
            String key = type + "\u001F" + description + "\u001F" + locationKey;
            if (evidenceKeys.add(key)) {
                evidence.add(new Evidence(type, description, source, target, location, weight));
            }
            signalWeights.merge(type, weight, Math::max);
        }

        private boolean has(EvidenceType type) {
            return signalWeights.containsKey(type);
        }
    }
}
