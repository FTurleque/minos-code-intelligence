package com.minos.architecture;

import com.minos.discovery.ProjectDiscovery;
import com.minos.domain.CodeEntityRef;
import com.minos.domain.CodeEntityType;
import com.minos.domain.Evidence;
import com.minos.domain.EvidenceType;
import com.minos.domain.InformationNature;
import com.minos.domain.Relationship;
import com.minos.domain.RelationshipKind;
import com.minos.domain.Symbol;
import com.minos.store.CodeKnowledgeSnapshot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Agrège uniquement les relations {@link RelationshipKind#DEPENDS_ON} déjà
 * persistées afin de produire un graphe module→module explicable.
 *
 * <p>Le service ne redérive pas une dépendance depuis une occurrence ou une
 * référence brute : cette responsabilité reste à la dérivation M3.</p>
 */
public final class ArchitectureDependencyService {

    private static final int SAMPLE_LIMIT = 5;

    public ArchitectureDependencyGraph build(
            ProjectDiscovery discovery,
            CodeKnowledgeSnapshot snapshot
    ) {
        Objects.requireNonNull(discovery, "discovery");
        Objects.requireNonNull(snapshot, "snapshot");

        String projectId = snapshot.projectId().toString();
        ArchitectureModuleResolver resolver = new ArchitectureModuleResolver(projectId, discovery);
        Map<String, Symbol> symbols = indexSymbols(snapshot.symbols());
        List<Relationship> dependencies = snapshot.relationships().stream()
                .filter(relationship -> relationship.kind() == RelationshipKind.DEPENDS_ON)
                .sorted(Comparator.comparing(Relationship::id))
                .toList();

        int interModuleCount = 0;
        int intraModuleCount = 0;
        int unassignedCount = 0;
        Map<EdgeKey, MutableEdge> edges = new LinkedHashMap<>();

        for (Relationship dependency : dependencies) {
            Symbol source = symbol(dependency.source(), symbols);
            Symbol target = symbol(dependency.target(), symbols);
            if (source == null || target == null) {
                unassignedCount++;
                continue;
            }

            ArchitectureModuleResolver.Assignment sourceAssignment = resolver.resolve(source).orElse(null);
            ArchitectureModuleResolver.Assignment targetAssignment = resolver.resolve(target).orElse(null);
            if (sourceAssignment == null || targetAssignment == null) {
                unassignedCount++;
                continue;
            }

            String sourceModuleId = sourceAssignment.moduleId();
            String targetModuleId = targetAssignment.moduleId();
            if (sourceModuleId.equals(targetModuleId)) {
                intraModuleCount++;
                continue;
            }

            interModuleCount++;
            EdgeKey key = new EdgeKey(sourceModuleId, targetModuleId);
            edges.computeIfAbsent(key, ignored -> new MutableEdge(projectId, key))
                    .accept(dependency, source.id(), target.id());
        }

        List<ArchitectureModuleDependency> moduleDependencies = edges.values().stream()
                .map(MutableEdge::toResult)
                .sorted(Comparator
                        .comparing(ArchitectureModuleDependency::sourceModuleId)
                        .thenComparing(ArchitectureModuleDependency::targetModuleId))
                .toList();

        return new ArchitectureDependencyGraph(
                projectId,
                snapshot.snapshotId(),
                dependencies.size(),
                interModuleCount,
                intraModuleCount,
                unassignedCount,
                moduleDependencies,
                InformationNature.DERIVED,
                List.of(new Evidence(
                        EvidenceType.DERIVATION_PATH,
                        "Aggregated " + dependencies.size()
                                + " persisted DEPENDS_ON relationships into "
                                + moduleDependencies.size() + " inter-module edges",
                        null,
                        null,
                        null,
                        1.0
                ))
        );
    }

    private static Map<String, Symbol> indexSymbols(List<Symbol> symbols) {
        Map<String, Symbol> indexed = new LinkedHashMap<>();
        symbols.stream()
                .sorted(Comparator.comparing(Symbol::id))
                .forEach(symbol -> {
                    Symbol previous = indexed.putIfAbsent(symbol.id(), symbol);
                    if (previous != null) {
                        throw new IllegalArgumentException("duplicate symbol id in snapshot: " + symbol.id());
                    }
                });
        return indexed;
    }

    private static Symbol symbol(CodeEntityRef reference, Map<String, Symbol> symbols) {
        if (reference == null || reference.type() != CodeEntityType.SYMBOL) {
            return null;
        }
        return symbols.get(reference.id());
    }

    private static double effectiveConfidence(Relationship relationship) {
        return relationship.nature() == InformationNature.FACTUAL
                ? 1.0
                : Objects.requireNonNullElse(relationship.confidence(), 0.0);
    }

    private static String edgeId(String projectId, EdgeKey key) {
        return "module-dependency:" + sha256(String.join("\u001F",
                projectId,
                key.sourceModuleId(),
                key.targetModuleId()
        ));
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private record EdgeKey(String sourceModuleId, String targetModuleId) {
        private EdgeKey {
            if (sourceModuleId == null || sourceModuleId.isBlank()) {
                throw new IllegalArgumentException("sourceModuleId must not be blank");
            }
            if (targetModuleId == null || targetModuleId.isBlank()) {
                throw new IllegalArgumentException("targetModuleId must not be blank");
            }
        }
    }

    private static final class MutableEdge {
        private final String projectId;
        private final EdgeKey key;
        private final List<String> relationshipIds = new ArrayList<>();
        private final Set<String> sourceSymbolIds = new LinkedHashSet<>();
        private final Set<String> targetSymbolIds = new LinkedHashSet<>();
        private double confidence = 1.0;

        private MutableEdge(String projectId, EdgeKey key) {
            this.projectId = projectId;
            this.key = key;
        }

        private void accept(Relationship relationship, String sourceSymbolId, String targetSymbolId) {
            relationshipIds.add(relationship.id());
            sourceSymbolIds.add(sourceSymbolId);
            targetSymbolIds.add(targetSymbolId);
            confidence = Math.min(confidence, effectiveConfidence(relationship));
        }

        private ArchitectureModuleDependency toResult() {
            List<String> orderedRelationshipIds = relationshipIds.stream().sorted().toList();
            List<String> sample = orderedRelationshipIds.stream().limit(SAMPLE_LIMIT).toList();
            CodeEntityRef sourceModule = new CodeEntityRef(CodeEntityType.MODULE, key.sourceModuleId());
            CodeEntityRef targetModule = new CodeEntityRef(CodeEntityType.MODULE, key.targetModuleId());

            return new ArchitectureModuleDependency(
                    edgeId(projectId, key),
                    key.sourceModuleId(),
                    key.targetModuleId(),
                    relationshipIds.size(),
                    sourceSymbolIds.size(),
                    targetSymbolIds.size(),
                    sample,
                    InformationNature.DERIVED,
                    confidence,
                    List.of(new Evidence(
                            EvidenceType.DERIVATION_PATH,
                            "Aggregated " + relationshipIds.size()
                                    + " persisted DEPENDS_ON relationships between modules",
                            sourceModule,
                            targetModule,
                            null,
                            confidence
                    ))
            );
        }
    }
}
