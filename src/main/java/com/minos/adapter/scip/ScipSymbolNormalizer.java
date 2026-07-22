package com.minos.adapter.scip;

import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.ProviderReference;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.domain.SymbolLocation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;

/**
 * Première passe de normalisation des symboles SCIP vers le domaine MINOS.
 *
 * <p>M0 utilise volontairement une identité structurelle de repli pour les symboles
 * locaux tant que la grammaire complète des identifiants SCIP n'est pas portée et
 * validée côté Java.</p>
 */
final class ScipSymbolNormalizer {

    private final ScipSymbolKindMapper kindMapper = new ScipSymbolKindMapper();

    Optional<Symbol> normalize(
            ScipSymbolFact fact,
            String projectId,
            String moduleId,
            String fileId,
            SymbolLocation declarationLocation,
            String providerId,
            String providerVersion,
            String indexRunId,
            boolean generated) {
        requireText(projectId, "projectId");
        requireText(providerId, "providerId");

        if (fact.displayName().isBlank() || fact.language().isBlank()) {
            return Optional.empty();
        }

        SymbolKind kind = kindMapper.map(fact.kind());
        SymbolIdentityQuality identityQuality;
        String identityMaterial;

        if (!fact.external() && !fact.relativePath().isBlank()) {
            identityQuality = SymbolIdentityQuality.STRUCTURAL_FALLBACK;
            identityMaterial = structuralIdentityMaterial(
                    projectId,
                    fact,
                    kind,
                    declarationLocation
            );
        } else {
            identityQuality = SymbolIdentityQuality.PROVIDER_SCOPED_FALLBACK;
            identityMaterial = String.join("\u001F",
                    projectId,
                    providerId,
                    fact.rawSymbol()
            );
        }

        String symbolKey = switch (identityQuality) {
            case CANONICAL -> throw new IllegalStateException("Canonical SCIP identity is not implemented in M0 baseline");
            case STRUCTURAL_FALLBACK -> "minos:structural:" + sha256(identityMaterial);
            case PROVIDER_SCOPED_FALLBACK -> "minos:provider:" + sha256(identityMaterial);
        };
        String id = "sym:" + sha256(projectId + "\u001F" + symbolKey);

        Origin origin = new Origin(
                providerId,
                "SCIP_INDEXER",
                blankToNull(providerVersion),
                blankToNull(indexRunId),
                OriginType.SCIP
        );

        return Optional.of(new Symbol(
                id,
                symbolKey,
                identityQuality,
                projectId,
                blankToNull(moduleId),
                blankToNull(fileId),
                null,
                kind,
                fact.displayName(),
                null,
                blankToNull(fact.signature()),
                fact.language(),
                declarationLocation,
                ResolutionStatus.RESOLVED,
                origin,
                fact.external(),
                generated,
                Set.of(new ProviderReference(providerId, fact.rawSymbol()))
        ));
    }

    private String structuralIdentityMaterial(
            String projectId,
            ScipSymbolFact fact,
            SymbolKind kind,
            SymbolLocation location) {
        String locationPart = location == null
                ? ""
                : location.startLine() + ":" + location.startColumn()
                    + "-" + location.endLine() + ":" + location.endColumn();

        return String.join("\u001F",
                projectId,
                fact.language(),
                kind.name(),
                fact.relativePath(),
                fact.displayName(),
                fact.signature(),
                locationPart
        );
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }
}
