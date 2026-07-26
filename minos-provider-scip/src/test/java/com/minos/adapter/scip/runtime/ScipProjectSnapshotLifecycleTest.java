package com.minos.adapter.scip.runtime;

import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexingRuntimePorts.IndexSnapshotStageRequest;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.store.FileSymbolSnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.scip_code.scip.Document;
import org.scip_code.scip.Index;
import org.scip_code.scip.Occurrence;
import org.scip_code.scip.SingleLineRange;
import org.scip_code.scip.SymbolInformation;
import org.scip_code.scip.SymbolRole;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScipProjectSnapshotLifecycleTest {

    @TempDir
    Path root;

    @Test
    void stagesAllProvidersBeforePublishingTheActiveProjectSnapshot() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        Path javaIndex = root.resolve("java.scip");
        Path tsIndex = root.resolve("typescript.scip");
        writeIndex(
                javaIndex,
                "java",
                "src/main/java/io/example/JavaService.java",
                "scip-java maven fixture 1.0 io/example/JavaService#",
                "JavaService"
        );
        writeIndex(
                tsIndex,
                "typescript",
                "src/TsService.ts",
                "scip-typescript npm fixture 1.0.0 src/`TsService.ts`/TsService#",
                "TsService"
        );

        ScipProjectSnapshotLifecycle lifecycle = new ScipProjectSnapshotLifecycle(root.resolve("home"));
        FileSymbolSnapshotStore active = new FileSymbolSnapshotStore(root.resolve("home/symbol-snapshots"));

        String stagedId = lifecycle.stage(new IndexSnapshotStageRequest(
                runId,
                projectId,
                List.of(
                        new IndexingArtifact(Language.JAVA, "scip-java", javaIndex),
                        new IndexingArtifact(Language.TYPESCRIPT, "scip-typescript", tsIndex)
                )
        ));

        assertTrue(active.loadActiveKnowledge(projectId).isEmpty(),
                "staging must not make provider data active");

        lifecycle.promote(projectId, runId, stagedId);
        var snapshot = active.loadActiveKnowledge(projectId).orElseThrow();

        assertEquals(stagedId, snapshot.snapshotId());
        assertEquals(2, snapshot.symbols().size());
        assertTrue(snapshot.symbols().stream().anyMatch(symbol -> "JavaService".equals(symbol.name())));
        assertTrue(snapshot.symbols().stream().anyMatch(symbol -> "TsService".equals(symbol.name())));
    }

    private static void writeIndex(
            Path file,
            String language,
            String relativePath,
            String rawSymbol,
            String displayName
    ) throws Exception {
        SymbolInformation symbol = SymbolInformation.newBuilder()
                .setSymbol(rawSymbol)
                .setDisplayName(displayName)
                .setKind(SymbolInformation.Kind.Class)
                .build();
        Occurrence definition = Occurrence.newBuilder()
                .setSymbol(rawSymbol)
                .setSymbolRoles(SymbolRole.Definition_VALUE)
                .setSingleLineRange(SingleLineRange.newBuilder()
                        .setLine(0)
                        .setStartCharacter(0)
                        .setEndCharacter(displayName.length()))
                .build();
        Document document = Document.newBuilder()
                .setLanguage(language)
                .setRelativePath(relativePath)
                .setPositionEncoding(org.scip_code.scip.PositionEncoding.UTF16CodeUnitOffsetFromLineStart)
                .addSymbols(symbol)
                .addOccurrences(definition)
                .build();
        Index index = Index.newBuilder().addDocuments(document).build();
        try (OutputStream output = Files.newOutputStream(file)) {
            index.writeTo(output);
        }
    }
}
