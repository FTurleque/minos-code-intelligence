package com.minos.store;

import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Q9 : ce que l'instantané garantit sur l'identité de ses symboles. Il refuse deux symboles de même identifiant ;
 * il ne vérifie PAS l'unicité des clés, qui découle de la dérivation de l'identifiant depuis la clé par le seul
 * producteur ({@code ScipSymbolNormalizerTest.theIdOfASymbolIsAFunctionOfItsKeyAndTheKeyOfItsIdentity}).
 */
class CodeKnowledgeSnapshotIdentityTest {

    private static final UUID PROJECT = UUID.randomUUID();
    private static final String SYM_A = "sym:a";
    private static final String SNAPSHOT = "snapshot-1";

    @Test
    void twoSymbolsWithTheSameIdAreRefusedByTheSnapshot() {
        List<Symbol> symbols = List.of(symbol(SYM_A, "key:a"), symbol(SYM_A, "key:b"));

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> new CodeKnowledgeSnapshot(PROJECT, SNAPSHOT, symbols, List.of(), List.of()));

        assertEquals("duplicate symbol id in snapshot: sym:a", refusal.getMessage());
    }

    @Test
    void theSnapshotDoesNotCheckKeysItself() {
        List<Symbol> symbols = List.of(symbol(SYM_A, "key:same"), symbol("sym:b", "key:same"));

        CodeKnowledgeSnapshot snapshot = new CodeKnowledgeSnapshot(PROJECT, SNAPSHOT, symbols, List.of(), List.of());

        assertEquals(2, snapshot.symbols().size(),
                "key uniqueness is a consequence of id derivation by the producer, not a snapshot check");
    }

    private static Symbol symbol(String id, String key) {
        return new Symbol(id, key, SymbolIdentityQuality.STRUCTURAL_FALLBACK, PROJECT.toString(), null, null, null,
                SymbolKind.CLASS, "Name", null, null, "java", null, ResolutionStatus.RESOLVED,
                new Origin("scip-java", "SCIP_INDEXER", null, null, OriginType.SCIP), false, false, Set.of());
    }
}
