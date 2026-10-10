package com.minos.output;

import com.minos.domain.CodeEntityRef;
import com.minos.domain.OccurrenceRole;
import com.minos.domain.Origin;
import com.minos.domain.SymbolLocation;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.minos.output.json.DeterministicJson.object;

/**
 * Formes JSON des petits objets que se partagent les renderers de résultats (emplacement, origine, entité,
 * rôles). Chaque forme est un objet ordonné, écrit ensuite par {@link DeterministicJson} : c'est l'endroit
 * unique où l'ordre et les clés de ces objets sont décidés (Q13).
 */
final class JsonShapes {

    private JsonShapes() {
    }

    /** Emplacement dans un fichier ; {@code null} reste {@code null}. */
    static Map<String, Object> location(SymbolLocation location) {
        if (location == null) {
            return null;
        }
        return object(
                "fileId", location.fileId(),
                "startLine", location.startLine(),
                "startColumn", location.startColumn(),
                "endLine", location.endLine(),
                "endColumn", location.endColumn(),
                "positionEncoding", location.positionEncoding().name());
    }

    /** Provenance d'un fait. */
    static Map<String, Object> origin(Origin origin) {
        return object(
                "providerId", origin.providerId(),
                "providerType", origin.providerType(),
                "providerVersion", origin.providerVersion(),
                "indexRunId", origin.indexRunId(),
                "sourceType", origin.sourceType().name());
    }

    /** Référence d'entité ; {@code null} reste {@code null}. */
    static Map<String, Object> entity(CodeEntityRef entity) {
        if (entity == null) {
            return null;
        }
        return object("type", entity.type().name(), "id", entity.id());
    }

    /** Rôles d'une occurrence, dans l'ordre de déclaration de l'énumération. */
    static List<String> roles(Set<OccurrenceRole> roles) {
        return roles.stream().sorted().map(Enum::name).toList();
    }
}
