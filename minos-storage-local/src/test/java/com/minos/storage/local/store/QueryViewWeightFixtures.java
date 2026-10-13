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
import com.minos.store.CodeKnowledgeSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Knowledge snapshots shaped like an indexed repository: per file, a few symbols, several occurrences per
 * symbol and relationships carrying evidence. The names come from a vocabulary so that the same fixture can
 * be produced with compact (Latin-1) and with two-byte (UTF-16) strings.
 */
final class QueryViewWeightFixtures {

    static final String[] ASCII = {"snapshot", "relationship", "occurrence", "provider", "resolve", "indexer"};
    static final String[] LATIN1 = {"été", "façade", "créé", "données", "référence", "dépôt"};
    static final String[] CJK = {"漢字", "符号表", "関係", "索引", "参照", "構造"};

    private static final Origin ORIGIN = new Origin("fixture-provider", "TEST", "1.0.0", "run-1", OriginType.OTHER);

    private QueryViewWeightFixtures() {
    }

    /** {@code files} source files, each with 20 symbols, 140 occurrences and 100 relationships. */
    static CodeKnowledgeSnapshot repository(UUID projectId, String snapshotId, int files, List<String[]> vocabularies) {
        Random random = new Random(0xA6_03L);
        String project = projectId.toString();
        List<Symbol> symbols = new ArrayList<>();
        List<SymbolOccurrence> occurrences = new ArrayList<>();
        List<Relationship> relationships = new ArrayList<>();
        for (int file = 0; file < files; file++) {
            String word = word(random, vocabularies);
            String fileId = String.format(Locale.ROOT, "module-%02d/src/main/java/com/minos/%s/File%04d.java",
                    file % 14, word, file);
            List<String> ids = new ArrayList<>();
            for (int index = 0; index < 20; index++) {
                String name = word(random, vocabularies) + index;
                String id = "sym-" + file + "-" + index;
                ids.add(id);
                symbols.add(new Symbol(id, "scip-java maven com.minos 1.0 " + fileId + "#" + name + "().",
                        SymbolIdentityQuality.CANONICAL, project, "module-" + file % 14, fileId, null,
                        SymbolKind.METHOD, name, "com.minos." + word + ".File" + file + "." + name,
                        "public " + word + " " + name + "(" + word(random, vocabularies) + " argument)", "java",
                        location(fileId, 10 + index * 7), ResolutionStatus.RESOLVED, ORIGIN, false, false, Set.of()));
            }
            for (int index = 0; index < 140; index++) {
                occurrences.add(new SymbolOccurrence("occ-" + file + "-" + index, project,
                        new ResolvedSymbolReference(ids.get(index % ids.size())), location(fileId, 5 + index),
                        Set.of(OccurrenceRole.REFERENCE), ResolutionStatus.RESOLVED, ORIGIN,
                        Set.of(new ProviderReference("fixture-provider", "local " + index))));
            }
            for (int index = 0; index < 100; index++) {
                CodeEntityRef source = new CodeEntityRef(CodeEntityType.SYMBOL, ids.get(index % ids.size()));
                CodeEntityRef target = new CodeEntityRef(CodeEntityType.SYMBOL, ids.get((index + 7) % ids.size()));
                SymbolLocation at = location(fileId, 5 + index);
                relationships.add(new Relationship("rel-" + file + "-" + index, project, source, target, null,
                        RelationshipKind.CALLS, at, ResolutionStatus.RESOLVED, InformationNature.FACTUAL, null, ORIGIN,
                        List.of(new Evidence(EvidenceType.DIRECT_CALL, "direct call to " + word(random, vocabularies),
                                source, target, at, 1.0))));
            }
        }
        return new CodeKnowledgeSnapshot(projectId, snapshotId, symbols, occurrences, relationships);
    }

    private static String word(Random random, List<String[]> vocabularies) {
        String[] vocabulary = vocabularies.get(random.nextInt(vocabularies.size()));
        return vocabulary[random.nextInt(vocabulary.length)];
    }

    private static SymbolLocation location(String fileId, int line) {
        return new SymbolLocation(fileId, line, 4, line, 24, PositionEncoding.UTF16_CODE_UNITS);
    }
}
