package com.minos.adapter.scip;

import com.minos.domain.CodeEntityRef;
import com.minos.domain.CodeEntityType;
import com.minos.domain.Evidence;
import com.minos.domain.EvidenceType;
import com.minos.domain.InformationNature;
import com.minos.domain.Relationship;
import com.minos.domain.RelationshipKind;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.Symbol;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Normalise les drapeaux relationnels SCIP sans inventer de sémantique de langage.
 *
 * <p>En particulier, {@code is_implementation} représente la relation utilisée
 * par « Find implementations ». Elle peut couvrir une implémentation d'interface,
 * un héritage ou un override selon l'indexeur ; elle n'est donc jamais promue en
 * {@link RelationshipKind#EXTENDS}.</p>
 */
final class ScipRelationshipNormalizer {

    List<Relationship> normalize(
            org.scip_code.scip.Relationship providerRelationship,
            Symbol source,
            Symbol target,
            String unresolvedTarget
    ) {
        Objects.requireNonNull(providerRelationship, "providerRelationship");
        Objects.requireNonNull(source, "source");

        if (providerRelationship.getSymbol().isBlank()) {
            return List.of();
        }
        String normalizedUnresolvedTarget = blankToNull(unresolvedTarget);
        if (target == null && normalizedUnresolvedTarget == null) {
            return List.of();
        }

        CodeEntityRef sourceReference = symbol(source.id());
        CodeEntityRef targetReference = target == null ? null : symbol(target.id());
        ResolutionStatus status = target == null
                ? ResolutionStatus.UNRESOLVED
                : ResolutionStatus.RESOLVED;
        List<Relationship> normalized = new ArrayList<>();

        for (Fact fact : facts(providerRelationship)) {
            Evidence evidence = new Evidence(
                    fact.evidenceType(),
                    fact.description(),
                    sourceReference,
                    targetReference,
                    source.location(),
                    1.0
            );
            normalized.add(new Relationship(
                    relationshipId(source, providerRelationship, fact.kind()),
                    source.projectId(),
                    sourceReference,
                    targetReference,
                    target == null ? normalizedUnresolvedTarget : null,
                    fact.kind(),
                    source.location(),
                    status,
                    InformationNature.FACTUAL,
                    null,
                    source.origin(),
                    List.of(evidence)
            ));
        }

        return List.copyOf(normalized);
    }

    int factCount(org.scip_code.scip.Relationship relationship) {
        Objects.requireNonNull(relationship, "relationship");
        return facts(relationship).size();
    }

    private static List<Fact> facts(org.scip_code.scip.Relationship relationship) {
        List<Fact> facts = new ArrayList<>(4);
        if (relationship.getIsReference()) {
            facts.add(new Fact(
                    RelationshipKind.REFERENCES,
                    EvidenceType.DIRECT_REFERENCE,
                    "SCIP marks the target for Find references"
            ));
        }
        if (relationship.getIsImplementation()) {
            facts.add(new Fact(
                    RelationshipKind.IMPLEMENTS,
                    EvidenceType.TYPE_RELATIONSHIP,
                    "SCIP marks the target for Find implementations"
            ));
        }
        if (relationship.getIsTypeDefinition()) {
            facts.add(new Fact(
                    RelationshipKind.TYPE_DEFINITION,
                    EvidenceType.TYPE_RELATIONSHIP,
                    "SCIP marks the target for Go to type definition"
            ));
        }
        if (relationship.getIsDefinition()) {
            facts.add(new Fact(
                    RelationshipKind.DEFINITION,
                    EvidenceType.PROVIDER_FACT,
                    "SCIP marks the target as a definition"
            ));
        }
        return facts;
    }

    private static String relationshipId(
            Symbol source,
            org.scip_code.scip.Relationship providerRelationship,
            RelationshipKind kind
    ) {
        String material = String.join("\u001F",
                source.projectId(),
                source.id(),
                source.origin().providerId(),
                providerRelationship.getSymbol(),
                kind.name()
        );
        return "rel:" + sha256(material);
    }

    private static CodeEntityRef symbol(String symbolId) {
        return new CodeEntityRef(CodeEntityType.SYMBOL, symbolId);
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

    private record Fact(
            RelationshipKind kind,
            EvidenceType evidenceType,
            String description
    ) {
    }
}
