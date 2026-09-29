package com.minos.storage.local.store;

import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.PositionEncoding;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.domain.SymbolLocation;
import com.minos.store.CodeKnowledgeSnapshot;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Snapshots sized against the persisted-snapshot ceiling. Every large symbol shares one ASCII string
 * instance, so a snapshot that encodes to more than 256 MiB costs only a few megabytes of heap.
 */
final class PersistedSizeFixtures {

    /** Below {@code MAX_STRING_CHARS} (8 Mi characters), the per-string ceiling of every codec. */
    static final int LARGE_STRING_CHARS = 8_000_000;
    private static final String LARGE = "s".repeat(LARGE_STRING_CHARS);

    private PersistedSizeFixtures() {
    }

    /** Seventeen symbols carrying an 8-million-character signature: about 272 MB in V2, above 256 MiB. */
    static CodeKnowledgeSnapshot oversized(UUID projectId, String snapshotId) {
        List<Symbol> symbols = new ArrayList<>();
        for (int index = 0; index < 17; index++) symbols.add(symbol(projectId, index, LARGE));
        return new CodeKnowledgeSnapshot(projectId, snapshotId, symbols, List.of(), List.of());
    }

    /** {@code count} large symbols, then one symbol whose signature has {@code tailChars} characters. */
    static CodeKnowledgeSnapshot withTail(UUID projectId, String snapshotId, int count, int tailChars) {
        return withTail(projectId, snapshotId, count, tailChars, false);
    }

    /**
     * {@code count} large symbols, one symbol whose signature has {@code tailChars} characters and, when
     * {@code paritySymbol}, one more small symbol: every symbol carries an odd number of fixed bytes, so
     * it flips the parity of a V2 encoding, whose strings only ever add an even number of bytes.
     */
    static CodeKnowledgeSnapshot withTail(UUID projectId, String snapshotId, int count, int tailChars,
                                          boolean paritySymbol) {
        List<Symbol> symbols = new ArrayList<>();
        for (int index = 0; index < count; index++) symbols.add(symbol(projectId, index, LARGE));
        symbols.add(symbol(projectId, count, LARGE.substring(0, tailChars)));
        if (paritySymbol) symbols.add(symbol(projectId, count + 1, null));
        return new CodeKnowledgeSnapshot(projectId, snapshotId, symbols, List.of(), List.of());
    }

    /**
     * A snapshot whose encoding by {@code codec} is exactly {@code target} bytes: large symbols, then one
     * symbol whose signature length absorbs the remainder (the smallest symbol count that makes it fit).
     */
    static CodeKnowledgeSnapshot ofEncodedSize(UUID projectId, String snapshotId, long target, SnapshotCodec codec)
            throws IOException {
        for (int count = 0; count < 64; count++) {
            for (boolean paritySymbol : new boolean[] {false, true}) {
                long base = codec.encodedSize(withTail(projectId, snapshotId, count, 0, paritySymbol));
                long perChar = codec.encodedSize(withTail(projectId, snapshotId, count, 1, paritySymbol)) - base;
                long remaining = target - base;
                if (remaining < 0L || remaining % perChar != 0L || remaining / perChar > LARGE_STRING_CHARS) continue;
                return withTail(projectId, snapshotId, count, (int) (remaining / perChar), paritySymbol);
            }
        }
        throw new IllegalArgumentException("no fixture encodes to exactly " + target + " bytes");
    }

    static Symbol symbol(UUID projectId, int index, String signature) {
        return new Symbol(
                String.format(java.util.Locale.ROOT, "sym-%03d", index),
                "key-" + index,
                SymbolIdentityQuality.CANONICAL,
                projectId.toString(),
                null,
                "src/Big.java",
                null,
                SymbolKind.METHOD,
                "big" + index,
                "com.minos.Big.big" + index,
                signature,
                "java",
                new SymbolLocation("src/Big.java", index + 1, 0, index + 1, 1, PositionEncoding.UTF16_CODE_UNITS),
                ResolutionStatus.RESOLVED,
                new Origin("fixture-provider", "TEST", "1", "run-1", OriginType.OTHER),
                false,
                false,
                Set.of()
        );
    }
}
