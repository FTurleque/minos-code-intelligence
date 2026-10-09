package com.minos.storage.local.store;

import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.PositionEncoding;
import com.minos.domain.ProviderReference;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.domain.SymbolLocation;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Child process of {@link SnapshotPublicationCrashTest}: publishes one snapshot of {@code count} symbols, then
 * prints {@value #DONE}. The parent kills it at various points of the publication.
 */
public final class SnapshotPublishChild {
    static final String DONE = "PUBLISHED";

    private SnapshotPublishChild() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        UUID projectId = UUID.fromString(args[1]);
        String snapshotId = args[2];
        int count = Integer.parseInt(args[3]);
        new FileSymbolSnapshotStore(root).publish(projectId, snapshotId, symbols(projectId, snapshotId, count));
        System.out.println(DONE);
    }

    static List<Symbol> symbols(UUID projectId, String snapshotId, int count) {
        List<Symbol> symbols = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            String id = snapshotId + "-symbol-" + index;
            symbols.add(new Symbol(
                    id,
                    projectId + "|java|METHOD|com.minos.Generated.m" + index + "|" + id,
                    SymbolIdentityQuality.STRUCTURAL_FALLBACK,
                    projectId.toString(),
                    "main",
                    "file-" + (index % 97),
                    null,
                    SymbolKind.METHOD,
                    "m" + index,
                    "com.minos.Generated.m" + index,
                    "(int)",
                    "java",
                    new SymbolLocation("file-" + (index % 97), 1 + index % 500, 4, 1 + index % 500, 20,
                            PositionEncoding.UTF16_CODE_UNITS),
                    ResolutionStatus.RESOLVED,
                    new Origin("fixture-provider", "TEST", "1.0", "run-1", OriginType.OTHER),
                    false,
                    false,
                    Set.of(new ProviderReference("fixture-provider", "ext-" + index))));
        }
        return symbols;
    }
}
