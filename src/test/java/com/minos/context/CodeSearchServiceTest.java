package com.minos.context;

import com.minos.domain.CodeEntityRef;
import com.minos.domain.CodeEntityType;
import com.minos.domain.InformationNature;
import com.minos.domain.OccurrenceRole;
import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.PositionEncoding;
import com.minos.domain.Relationship;
import com.minos.domain.RelationshipKind;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.ResolvedSymbolReference;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.domain.SymbolLocation;
import com.minos.domain.SymbolOccurrence;
import com.minos.domain.SymbolSearchCriteria;
import com.minos.store.InMemoryCodeKnowledgeStore;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeSearchServiceTest {

    private static final String PROJECT = "project-1";

    @Test
    void composesSourceUsagesAndBreadthFirstRelationshipsAtBoundedDepth()
            throws IOException {
        InMemoryCodeKnowledgeStore store = store();
        CodeSearchService service = new CodeSearchService(store, new FixtureSourceReader());

        CodeSearchResponse depthOne = service.search(PROJECT, criteria(1, 4_000, true));
        CodeSearchResponse depthTwo = service.search(PROJECT, criteria(2, 4_000, true));

        CodeContextResult first = depthOne.contexts().getFirst();
        assertEquals("symbol-a", first.symbol().id());
        assertTrue(first.source().content().contains("class A"));
        assertEquals(1, first.usages().size());
        assertEquals(List.of("rel-a-b"), first.relationships().stream()
                .map(result -> result.relationship().id()).toList());
        assertEquals(List.of("rel-a-b", "rel-b-c"), depthTwo.contexts().getFirst()
                .relationships().stream().map(result -> result.relationship().id()).toList());
        assertEquals(List.of(1, 2), depthTwo.contexts().getFirst().relationships().stream()
                .map(ContextRelationshipResult::depth).toList());
        assertTrue(depthTwo.estimatedTokens() <= depthTwo.tokenBudget());
        assertTrue(depthTwo.estimatedTokensAvoided() > 0);
    }

    @Test
    void enforcesTokenBudgetAndCanOmitSourceEntirely() throws IOException {
        FixtureSourceReader reader = new FixtureSourceReader();
        CodeSearchService service = new CodeSearchService(store(), reader);

        CodeSearchResponse bounded = service.search(PROJECT, criteria(2, 256, true));
        CodeSearchResponse withoutSource = service.search(PROJECT, criteria(0, 512, false));

        assertTrue(bounded.estimatedTokens() <= 256);
        assertTrue(bounded.truncated());
        assertEquals(1, reader.calls.get());
        assertFalse(withoutSource.contexts().isEmpty());
        assertEquals(null, withoutSource.contexts().getFirst().source());
    }

    @Test
    void reportsWhenRootResultLimitCutsAdditionalMatches() throws IOException {
        CodeSearchResponse response = new CodeSearchService(store(), new FixtureSourceReader())
                .search(PROJECT, new CodeSearchCriteria(
                        SymbolSearchCriteria.lexical("com.minos", 1),
                        0, 0, 0, 0, 512, false));

        assertEquals(1, response.count());
        assertTrue(response.truncated());
    }

    private static CodeSearchCriteria criteria(int depth, int tokens, boolean source) {
        return new CodeSearchCriteria(
                new SymbolSearchCriteria("A", "com.minos.A", SymbolKind.CLASS, "main", 1),
                depth,
                3,
                5,
                1,
                tokens,
                source
        );
    }

    private static InMemoryCodeKnowledgeStore store() {
        InMemoryCodeKnowledgeStore store = new InMemoryCodeKnowledgeStore();
        Symbol a = symbol("a");
        Symbol b = symbol("b");
        Symbol c = symbol("c");
        store.putSymbols(List.of(a, b, c));
        store.putRelationships(List.of(
                relationship("rel-a-b", a, b, RelationshipKind.CALLS),
                relationship("rel-b-c", b, c, RelationshipKind.REFERENCES)
        ));
        store.putOccurrences(List.of(new SymbolOccurrence(
                "usage-a", PROJECT, new ResolvedSymbolReference(a.id()),
                location("src/UseA.java", 8), Set.of(OccurrenceRole.REFERENCE),
                ResolutionStatus.RESOLVED, origin(), Set.of()
        )));
        return store;
    }

    private static Symbol symbol(String suffix) {
        String upper = suffix.toUpperCase();
        return new Symbol(
                "symbol-" + suffix, PROJECT + "|java|CLASS|com.minos." + upper,
                SymbolIdentityQuality.STRUCTURAL_FALLBACK, PROJECT, "main",
                "src/" + upper + ".java", null, SymbolKind.CLASS, upper,
                "com.minos." + upper, null, "java", location("src/" + upper + ".java", 2),
                ResolutionStatus.RESOLVED, origin(), false, false, Set.of()
        );
    }

    private static Relationship relationship(
            String id,
            Symbol source,
            Symbol target,
            RelationshipKind kind
    ) {
        return new Relationship(
                id, PROJECT, ref(source), ref(target), null, kind, source.location(),
                ResolutionStatus.RESOLVED, InformationNature.FACTUAL, null,
                origin(), List.of()
        );
    }

    private static CodeEntityRef ref(Symbol symbol) {
        return new CodeEntityRef(CodeEntityType.SYMBOL, symbol.id());
    }

    private static SymbolLocation location(String file, int line) {
        return new SymbolLocation(file, line, 0, line, 10, PositionEncoding.UTF16_CODE_UNITS);
    }

    private static Origin origin() {
        return new Origin("fixture", "TEST", "1", "run-1", OriginType.OTHER);
    }

    private static final class FixtureSourceReader implements SourceReader {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public Optional<SourceExcerpt> readExcerpt(
                SymbolLocation location,
                int contextLines,
                int maxTokens
        ) {
            calls.incrementAndGet();
            String complete = "package com.minos;\nclass A {}\n" + "x".repeat(600);
            String content = TokenEstimator.truncate(complete, maxTokens);
            int tokens = TokenEstimator.estimate(content);
            return Optional.of(new SourceExcerpt(
                    location.fileId(), 1, 3, content, false,
                    content.length() < complete.length(), tokens, 40, 1_000));
        }

        @Override
        public SourceExcerpt readFull(String fileId) {
            throw new UnsupportedOperationException();
        }
    }
}
