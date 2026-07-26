package com.minos.query;

import com.minos.domain.Origin;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.domain.SymbolLocation;

import java.util.Objects;

/**
 * Résultat compact et indépendant de la persistance pour une requête de symbole.
 *
 * <p>Le DTO conserve l'identité, la déclaration et la provenance utiles à un
 * consommateur. Il n'expose pas les références opaques du fournisseur ni le
 * contenu complet du fichier.</p>
 */
public record SymbolResult(
        String id,
        String symbolKey,
        SymbolIdentityQuality identityQuality,
        String projectId,
        String moduleId,
        String fileId,
        SymbolKind kind,
        String name,
        String qualifiedName,
        String signature,
        String language,
        SymbolLocation location,
        ResolutionStatus resolutionStatus,
        Origin origin,
        boolean external,
        boolean generated) {

    public SymbolResult {
        requireText(id, "id");
        requireText(symbolKey, "symbolKey");
        requireText(projectId, "projectId");
        requireText(name, "name");
        requireText(language, "language");
        Objects.requireNonNull(identityQuality, "identityQuality");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(resolutionStatus, "resolutionStatus");
        Objects.requireNonNull(origin, "origin");
    }

    public static SymbolResult from(Symbol symbol) {
        Objects.requireNonNull(symbol, "symbol");
        return new SymbolResult(
                symbol.id(),
                symbol.symbolKey(),
                symbol.identityQuality(),
                symbol.projectId(),
                symbol.moduleId(),
                symbol.fileId(),
                symbol.kind(),
                symbol.name(),
                symbol.qualifiedName(),
                symbol.signature(),
                symbol.language(),
                symbol.location(),
                symbol.resolutionStatus(),
                symbol.origin(),
                symbol.external(),
                symbol.generated()
        );
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }
}
