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
 * Normalisation des symboles SCIP vers le domaine MINOS.
 *
 * <p>M2 extrait les noms qualifiés depuis la grammaire standard des descripteurs.
 * La qualité d'identité reste néanmoins un repli explicite tant que l'équivalence
 * des signatures et symboles entre fournisseurs n'est pas mesurée.</p>
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
        return normalize(
                fact,
                projectId,
                moduleId,
                fileId,
                fact == null ? null : fact.relativePath(),
                "",
                declarationLocation,
                providerId,
                providerVersion,
                indexRunId,
                generated
        );
    }

    Optional<Symbol> normalize(
            ScipSymbolFact fact,
            String projectId,
            String moduleId,
            String fileId,
            String projectRelativePath,
            SymbolLocation declarationLocation,
            String providerId,
            String providerVersion,
            String indexRunId,
            boolean generated) {
        return normalize(
                fact,
                projectId,
                moduleId,
                fileId,
                projectRelativePath,
                "",
                declarationLocation,
                providerId,
                providerVersion,
                indexRunId,
                generated
        );
    }

    Optional<Symbol> normalize(
            ScipSymbolFact fact,
            String projectId,
            String moduleId,
            String fileId,
            String projectRelativePath,
            String projectRelativeRoot,
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

        SymbolKind kind = kindMapper.map(fact.kind(), fact.rawSymbol());
        String qualifiedName = ScipQualifiedNameExtractor.extract(fact.rawSymbol(), fact.language())
                .orElse(null);
        SymbolIdentityQuality identityQuality;
        String identityMaterial;

        if (!fact.external() && !fact.relativePath().isBlank()) {
            identityQuality = SymbolIdentityQuality.STRUCTURAL_FALLBACK;
            identityMaterial = structuralIdentityMaterial(
                    projectId,
                    fact,
                    kind,
                    declarationLocation,
                    qualifiedName,
                    projectRelativePath,
                    projectRelativeRoot
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
                qualifiedName,
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
            SymbolLocation location,
            String qualifiedName,
            String projectRelativePath,
            String projectRelativeRoot) {
        String locationPart = location == null
                ? ""
                : location.startLine() + ":" + location.startColumn()
                    + "-" + location.endLine() + ":" + location.endColumn();
        String scopePart = projectRelativeRoot == null ? "" : projectRelativeRoot.trim();

        if (qualifiedName != null) {
            if (!scopePart.isBlank()) {
                // SCIP providers such as scip-typescript intentionally omit the module-file
                // descriptor from MINOS qualified names. Keep historical root identities stable,
                // but use the portable execution scope to distinguish equal qualified names that
                // legitimately live in different modules of the same registered project.
                return String.join("\u001F",
                        projectId,
                        fact.language(),
                        kind.name(),
                        qualifiedName,
                        fact.signature(),
                        "scope=" + scopePart,
                        fact.signature().isBlank() ? locationPart : ""
                );
            }
            return String.join("\u001F",
                    projectId,
                    fact.language(),
                    kind.name(),
                    qualifiedName,
                    fact.signature(),
                    fact.signature().isBlank() ? locationPart : ""
            );
        }

        String structuralPath = projectRelativePath == null || projectRelativePath.isBlank()
                ? fact.relativePath()
                : projectRelativePath;
        return String.join("\u001F",
                projectId,
                fact.language(),
                kind.name(),
                structuralPath,
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
