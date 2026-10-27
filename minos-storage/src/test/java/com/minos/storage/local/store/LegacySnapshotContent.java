package com.minos.storage.local.store;

import com.minos.domain.CodeEntityRef;
import com.minos.domain.CodeEntityType;
import com.minos.domain.Evidence;
import com.minos.domain.EvidenceType;
import com.minos.domain.InformationNature;
import com.minos.domain.OccurrenceRole;
import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.PositionEncoding;
import com.minos.domain.ProviderReference;
import com.minos.domain.Relationship;
import com.minos.domain.RelationshipKind;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.ResolvedSymbolReference;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.domain.SymbolLocation;
import com.minos.domain.SymbolOccurrence;
import com.minos.domain.UnresolvedSymbolReference;
import com.minos.store.CodeKnowledgeSnapshot;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Contenu des snapshots de référence écrits par le code de {@code d9ae1005} (lot A6, étape 2). Autonome : il
 * ne dépend que du domaine, pour se compiler aussi contre la base et y produire les fixtures
 * ({@link LegacySnapshotFixtureGenerator}). Les chaînes piégées couvrent accents, emoji (paire de
 * substitution), CJK, NUL, U+FFFF et, dans une variante seulement, un surrogate isolé.
 */
final class LegacySnapshotContent {

    static final UUID V1_PROJECT = UUID.fromString("a6000000-0000-4000-8000-000000000001");
    static final UUID V2_PROJECT = UUID.fromString("a6000000-0000-4000-8000-000000000002");
    static final UUID V2_LONE_SURROGATE_PROJECT = UUID.fromString("a6000000-0000-4000-8000-000000000003");
    static final String V1_SNAPSHOT = "legacy-v1";
    static final String V2_SNAPSHOT = "legacy-v2";
    static final String V2_LONE_SURROGATE_SNAPSHOT = "legacy-v2-lone-surrogate";

    /** Accents, emoji (paire de substitution), CJK, NUL et U+FFFF : bien formé en UTF-16. */
    static final String TRAPS = "é à ü — 😀 漢字 nul\u0000 max￿";
    static final String LONE_SURROGATE = "orphelin\uD800fin";

    private LegacySnapshotContent() {
    }

    static List<Symbol> symbols(UUID projectId, String extra) {
        String project = projectId.toString();
        return List.of(
                new Symbol("sym-a", "key-a " + TRAPS, SymbolIdentityQuality.CANONICAL, project, "module-é", "src/Été.java",
                        null, SymbolKind.CLASS, "Été", "com.minos.Été", null, "java",
                        location("src/Été.java", 1), ResolutionStatus.RESOLVED, origin(), false, false,
                        Set.of(new ProviderReference("scip-java", "semanticdb maven 漢字 " + extra))),
                new Symbol("sym-b", "key-b", SymbolIdentityQuality.STRUCTURAL_FALLBACK, project, null, null,
                        "sym-a", SymbolKind.METHOD, "émoji😀", "com.minos.Été.émoji😀", "(String" + TRAPS + ")", "java",
                        null, ResolutionStatus.RESOLVED, new Origin("scip-java", null, null, null, OriginType.OTHER),
                        true, true, Set.of()));
    }

    static CodeKnowledgeSnapshot knowledge(UUID projectId, String snapshotId, String extra) {
        String project = projectId.toString();
        CodeEntityRef a = new CodeEntityRef(CodeEntityType.SYMBOL, "sym-a");
        CodeEntityRef b = new CodeEntityRef(CodeEntityType.SYMBOL, "sym-b");
        List<SymbolOccurrence> occurrences = List.of(
                new SymbolOccurrence("occ-1", project, new ResolvedSymbolReference("sym-a"), location("src/Été.java", 3),
                        Set.of(OccurrenceRole.DEFINITION), ResolutionStatus.RESOLVED, origin(),
                        Set.of(new ProviderReference("scip-java", "occ " + TRAPS))),
                new SymbolOccurrence("occ-2", project,
                        new UnresolvedSymbolReference("Manquant漢", null, "java", "raison " + extra,
                                Set.of(new ProviderReference("scip-java", "manquant"))),
                        location("src/Été.java", 4), Set.of(), ResolutionStatus.UNRESOLVED, origin(), Set.of()));
        List<Relationship> relationships = List.of(
                new Relationship("rel-1", project, b, a, null, RelationshipKind.CALLS, location("src/Été.java", 5),
                        ResolutionStatus.RESOLVED, InformationNature.DERIVED, 0.5, origin(),
                        List.of(new Evidence(EvidenceType.DIRECT_CALL, "appel " + TRAPS, b, a, null, 0.5))),
                new Relationship("rel-2", project, b, null, "Inconnu😀", RelationshipKind.REFERENCES, null,
                        ResolutionStatus.UNRESOLVED, InformationNature.FACTUAL, null, origin(), List.of()));
        return new CodeKnowledgeSnapshot(projectId, snapshotId, symbols(projectId, extra), occurrences, relationships);
    }

    static CodeKnowledgeSnapshot v2() {
        return knowledge(V2_PROJECT, V2_SNAPSHOT, "sans orphelin");
    }

    static CodeKnowledgeSnapshot v2WithLoneSurrogate() {
        return knowledge(V2_LONE_SURROGATE_PROJECT, V2_LONE_SURROGATE_SNAPSHOT, LONE_SURROGATE);
    }

    static List<Symbol> v1Symbols() {
        return symbols(V1_PROJECT, "v1 " + TRAPS);
    }

    private static SymbolLocation location(String fileId, int line) {
        return new SymbolLocation(fileId, line, 0, line, 4, PositionEncoding.UTF16_CODE_UNITS);
    }

    private static Origin origin() {
        return new Origin("scip-java", "SCIP", "0.13.1", "application-scip-0123456789abcdef01234567", OriginType.OTHER);
    }
}
