package com.minos.store;

import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.domain.SymbolSearchCriteria;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rang de correspondance de la recherche de symboles par texte : ordre et contenu des résultats figés, et rang
 * calculé au plus une fois par candidat, jamais sans texte.
 */
class SymbolMatchRankTest {

    private static final UUID PROJECT = UUID.fromString("00000000-0000-0000-0000-00000000a010");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-00000000a011");
    private static final Origin ORIGIN = new Origin("fixture", "TEST", "1", "run-1", OriginType.OTHER);

    /** Un symbole par rang (0 à 6), des ex æquo sur chaque clé secondaire et des symboles sans correspondance. */
    private static List<Symbol> rankedFixture() {
        return List.of(
                symbol("no-match", "key:none", "Unrelated", "com.acme.Unrelated", null, false, false),
                symbol("rank6", "key:greet-6", "Other", "com.acme.Other", null, false, false),
                symbol("rank5", "key:r5", "Hello", "com.greet.Hello", null, false, false),
                symbol("rank4", "key:r4", "sayGreeting", "com.acme.sayGreeting", null, false, false),
                symbol("rank3", "key:r3", "Hello", "greetings.Hello", null, false, false),
                symbol("rank2", "key:r2", "GreeterA", "com.acme.GreeterA", null, false, false),
                symbol("rank0", "key:r0", "Alias", "GREET", null, false, false),
                symbol("tie-external", "key:t1", "greet", "com.a.greet", null, true, false),
                symbol("tie-generated", "key:t2", "greet", "com.a.greet", null, false, true),
                symbol("tie-both", "key:t3", "greet", "com.a.greet", null, true, true),
                symbol("tie-null-qn", "key:t4", "Greet", null, null, false, false),
                symbol("tie-qn-b-null-sig", "key:t5", "greet", "com.b.greet", null, false, false),
                symbol("tie-qn-b-sig-z", "key:t6", "greet", "com.b.greet", "void greet()", false, false),
                symbol("tie-qn-b-sig-y", "key:t7", "greet", "com.b.greet", "void greet()", false, false),
                symbol("tie-qn-a", "key:t8", "greet", "com.a.greet", "void greet()", false, false),
                symbol("tie-name-upper", "key:t9", "GREET", "com.c.greet", null, false, false),
                symbol("tie-name-lower", "key:t10", "greet", "com.c.greet", null, false, false),
                symbol("tie-rank2-b", "key:t11", "greeter", "com.acme.greeter", null, false, false),
                symbol("tie-rank2-a", "key:t12", "greeter", "com.acme.greeter", null, false, false),
                symbol("ext-rank6", "key:ext-greet", "Elsewhere", "org.ext.Elsewhere", null, true, false));
    }

    @Test
    void textSearchKeepsTheCapturedContentAndOrder() {
        InMemoryCodeKnowledgeStore store = storeWithAnotherProject();

        List<String> ids = ids(store.findSymbols(PROJECT.toString(), SymbolSearchCriteria.lexical("greet", 100)));

        assertEquals(List.of(
                "rank0",
                "tie-qn-a", "tie-qn-b-sig-y", "tie-qn-b-sig-z", "tie-qn-b-null-sig", "tie-name-upper",
                "tie-name-lower", "tie-null-qn", "tie-generated", "tie-external", "tie-both",
                "rank2", "tie-rank2-a", "tie-rank2-b",
                "rank3",
                "rank4",
                "rank5",
                "rank6", "ext-rank6"), ids);
    }

    @Test
    void textSearchAppliesTheLimitAfterTheCompleteOrder() {
        InMemoryCodeKnowledgeStore store = storeWithAnotherProject();

        assertEquals(List.of("rank0", "tie-qn-a", "tie-qn-b-sig-y"),
                ids(store.findSymbols(PROJECT.toString(), SymbolSearchCriteria.lexical("Greet", 3))));
        assertEquals(List.of("rank0"),
                ids(store.findSymbols(PROJECT.toString(), SymbolSearchCriteria.lexical("GREET", 1))));
    }

    @Test
    void symbolMatchedOnlyByItsKeyComesAfterNameMatchesAndUnmatchedSymbolsStayOut() {
        InMemoryCodeKnowledgeStore store = storeWithAnotherProject();

        List<String> ids = ids(store.findSymbols(PROJECT.toString(), SymbolSearchCriteria.lexical("greet", 100)));

        assertEquals(List.of("rank6", "ext-rank6"), ids.subList(ids.size() - 2, ids.size()));
        assertFalse(ids.contains("no-match"));
        assertFalse(ids.contains("other-project"));
        assertEquals(List.of("rank6"),
                ids(store.findSymbols(PROJECT.toString(), SymbolSearchCriteria.lexical("Greet-6", 10))));
        assertEquals(List.of(),
                ids(store.findSymbols(PROJECT.toString(), SymbolSearchCriteria.lexical("absent", 10))));
    }

    @Test
    void searchWithoutTextKeepsTheSecondaryOrder() {
        InMemoryCodeKnowledgeStore store = storeWithAnotherProject();

        List<String> ids = ids(store.findSymbols(PROJECT.toString(),
                new SymbolSearchCriteria(null, null, SymbolKind.METHOD, null, 100)));

        assertEquals(List.of(
                "rank0", "tie-qn-a", "rank2", "rank6", "no-match", "tie-rank2-a", "tie-rank2-b", "rank4",
                "tie-qn-b-sig-y", "tie-qn-b-sig-z", "tie-qn-b-null-sig", "tie-name-upper", "tie-name-lower",
                "rank5", "rank3", "tie-null-qn", "tie-generated", "tie-external", "ext-rank6", "tie-both"), ids);
        assertEquals(List.of("tie-qn-a"), ids(store.findSymbols(PROJECT.toString(),
                new SymbolSearchCriteria(null, "com.a.greet", null, null, 1))));
    }

    @Test
    void textSearchComputesTheRankOncePerCandidate() {
        List<Symbol> symbols = new ArrayList<>();
        for (int index = 0; index < 200; index++) {
            symbols.add(symbol("s" + index, "key:s" + index, "name" + index, "com.acme.name" + index, null,
                    false, false));
        }
        CountingRank rank = new CountingRank();
        InMemoryCodeKnowledgeStore store = new InMemoryCodeKnowledgeStore(snapshot(PROJECT, symbols), rank);

        List<Symbol> found = store.findSymbols(PROJECT.toString(), SymbolSearchCriteria.lexical("name1", 1_000));

        assertEquals(111, found.size());
        assertEquals("s1", found.getFirst().id());
        assertTrue(rank.calls() <= symbols.size(), "rank calls: " + rank.calls());
        assertTrue(rank.callsBySymbol.values().stream().allMatch(count -> count == 1), rank.callsBySymbol::toString);
    }

    @Test
    void rankedFixtureComputesTheRankOncePerCandidate() {
        CountingRank rank = new CountingRank();
        InMemoryCodeKnowledgeStore store = new InMemoryCodeKnowledgeStore(snapshot(PROJECT, rankedFixture()), rank);

        store.findSymbols(PROJECT.toString(), SymbolSearchCriteria.lexical("greet", 100));

        assertTrue(rank.calls() <= rankedFixture().size(), "rank calls: " + rank.calls());
        assertTrue(rank.callsBySymbol.values().stream().allMatch(count -> count == 1), rank.callsBySymbol::toString);
    }

    @Test
    void searchWithoutTextComputesNoRank() {
        CountingRank rank = new CountingRank();
        InMemoryCodeKnowledgeStore store = new InMemoryCodeKnowledgeStore(snapshot(PROJECT, rankedFixture()), rank);

        List<Symbol> found = store.findSymbols(PROJECT.toString(),
                new SymbolSearchCriteria(null, null, SymbolKind.METHOD, null, 100));

        assertEquals(20, found.size());
        assertEquals(0, rank.calls());
    }

    @Test
    void absentCriteriaIsRefusedBeforeAnyRank() {
        CountingRank rank = new CountingRank();
        InMemoryCodeKnowledgeStore store = new InMemoryCodeKnowledgeStore(snapshot(PROJECT, rankedFixture()), rank);

        assertThrows(IllegalArgumentException.class, () -> store.findSymbols(PROJECT.toString(), null));
        assertEquals(0, rank.calls());
    }

    /** Fonction de rang qui compte ses appels par symbole et délègue au rang de production. */
    private static final class CountingRank implements InMemoryCodeKnowledgeStore.SymbolMatchRank {

        private final Map<String, Integer> callsBySymbol = new HashMap<>();

        @Override
        public int rank(Symbol symbol, String normalizedQuery) {
            callsBySymbol.merge(symbol.id(), 1, Integer::sum);
            return InMemoryCodeKnowledgeStore.matchRank(symbol, normalizedQuery);
        }

        int calls() {
            return callsBySymbol.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    /** La vue du jeu classé, plus un symbole d'un autre projet que la recherche ne doit jamais rendre. */
    private static InMemoryCodeKnowledgeStore storeWithAnotherProject() {
        InMemoryCodeKnowledgeStore store = new InMemoryCodeKnowledgeStore();
        List<Symbol> symbols = new ArrayList<>(rankedFixture());
        symbols.add(foreign("other-project", "Greet"));
        store.putSymbols(symbols);
        return store;
    }

    private static CodeKnowledgeSnapshot snapshot(UUID project, List<Symbol> symbols) {
        return new CodeKnowledgeSnapshot(project, "snapshot-1", symbols, List.of(), List.of());
    }

    private static List<String> ids(List<Symbol> symbols) {
        return symbols.stream().map(Symbol::id).toList();
    }

    private static Symbol symbol(String id, String key, String name, String qualifiedName, String signature,
                                 boolean external, boolean generated) {
        return new Symbol(id, key, SymbolIdentityQuality.STRUCTURAL_FALLBACK, PROJECT.toString(), null,
                "src/" + id + ".java", null, SymbolKind.METHOD, name, qualifiedName, signature, "java", null,
                ResolutionStatus.RESOLVED, ORIGIN, external, generated, Set.of());
    }

    private static Symbol foreign(String id, String name) {
        return new Symbol(id, "key:" + id, SymbolIdentityQuality.STRUCTURAL_FALLBACK, OTHER.toString(), null,
                "src/" + id + ".java", null, SymbolKind.METHOD, name, "com.other." + name, null, "java", null,
                ResolutionStatus.RESOLVED, ORIGIN, false, false, Set.of());
    }
}
