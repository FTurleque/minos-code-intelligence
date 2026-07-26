package com.minos.query;

import com.minos.domain.CodeEntityRef;
import com.minos.domain.Evidence;
import com.minos.domain.EvidenceType;
import com.minos.domain.InformationNature;
import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.Relationship;
import com.minos.domain.RelationshipKind;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Dérive une vue directe {@link RelationshipKind#DEPENDS_ON} depuis les faits
 * relationnels qui matérialisent effectivement une dépendance de code.
 *
 * <p>Une seule dépendance est produite par paire source/cible. Les faits ayant
 * conduit à cette dérivation restent disponibles sous forme de preuves
 * structurées et les relations factuelles d'origine ne sont jamais remplacées.</p>
 */
public final class DependencyDerivationService {

    private static final Set<RelationshipKind> DEPENDENCY_FACTS = EnumSet.of(
            RelationshipKind.IMPORTS,
            RelationshipKind.REFERENCES,
            RelationshipKind.EXTENDS,
            RelationshipKind.IMPLEMENTS,
            RelationshipKind.CALLS,
            RelationshipKind.RETURNS,
            RelationshipKind.ACCEPTS,
            RelationshipKind.READS,
            RelationshipKind.WRITES,
            RelationshipKind.INSTANTIATES,
            RelationshipKind.INJECTS,
            RelationshipKind.TYPE_DEFINITION
    );

    private static final Comparator<Relationship> FACT_ORDER = Comparator
            .comparing(Relationship::projectId)
            .thenComparing(relationship -> relationship.source().type())
            .thenComparing(relationship -> relationship.source().id())
            .thenComparing(Relationship::kind)
            .thenComparing(relationship -> relationship.target() == null)
            .thenComparing(
                    relationship -> relationship.target() == null
                            ? null
                            : relationship.target().type(),
                    Comparator.nullsLast(Comparator.naturalOrder())
            )
            .thenComparing(
                    relationship -> relationship.target() == null
                            ? null
                            : relationship.target().id(),
                    Comparator.nullsLast(String::compareTo)
            )
            .thenComparing(Relationship::unresolvedTarget, Comparator.nullsLast(String::compareTo))
            .thenComparing(Relationship::id);

    public List<Relationship> derive(Collection<Relationship> relationships) {
        if (relationships == null || relationships.isEmpty()) {
            return List.of();
        }

        Map<DependencyKey, List<Relationship>> factsByDependency = new LinkedHashMap<>();
        relationships.stream()
                .filter(Objects::nonNull)
                .filter(this::isDependencyFact)
                .sorted(FACT_ORDER)
                .forEach(relationship -> factsByDependency
                        .computeIfAbsent(DependencyKey.from(relationship), ignored -> new ArrayList<>())
                        .add(relationship));

        return factsByDependency.entrySet().stream()
                .map(entry -> derive(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(Relationship::id))
                .toList();
    }

    private boolean isDependencyFact(Relationship relationship) {
        return relationship.nature() == InformationNature.FACTUAL
                && DEPENDENCY_FACTS.contains(relationship.kind())
                && (relationship.target() == null
                        || !relationship.source().equals(relationship.target()));
    }

    private Relationship derive(DependencyKey key, List<Relationship> facts) {
        Relationship first = facts.getFirst();
        List<Evidence> evidence = facts.stream()
                .map(this::evidence)
                .toList();
        Origin origin = new Origin(
                "minos",
                "RELATIONSHIP_DERIVATION",
                "M3",
                first.origin().indexRunId(),
                OriginType.DERIVED_BY_MINOS
        );

        return new Relationship(
                dependencyId(key),
                key.projectId(),
                key.source(),
                key.target(),
                key.unresolvedTarget(),
                RelationshipKind.DEPENDS_ON,
                first.location(),
                first.resolutionStatus(),
                InformationNature.DERIVED,
                1.0,
                origin,
                evidence
        );
    }

    private Evidence evidence(Relationship fact) {
        return new Evidence(
                EvidenceType.DERIVATION_PATH,
                "Direct " + fact.kind().name() + " fact implies a code dependency",
                fact.source(),
                fact.target(),
                fact.location(),
                1.0
        );
    }

    private static String dependencyId(DependencyKey key) {
        CodeEntityRef target = key.target();
        String material = String.join("\u001F",
                key.projectId(),
                key.source().type().name(),
                key.source().id(),
                target == null ? "" : target.type().name(),
                target == null ? "" : target.id(),
                key.unresolvedTarget() == null ? "" : key.unresolvedTarget(),
                RelationshipKind.DEPENDS_ON.name()
        );
        return "rel:" + sha256(material);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private record DependencyKey(
            String projectId,
            CodeEntityRef source,
            CodeEntityRef target,
            String unresolvedTarget
    ) {
        private static DependencyKey from(Relationship relationship) {
            return new DependencyKey(
                    relationship.projectId(),
                    relationship.source(),
                    relationship.target(),
                    relationship.unresolvedTarget()
            );
        }
    }
}
