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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScipProjectSnapshotLifecycleTest {

    @TempDir
    Path root;

    @Test
    void stagesAllProvidersBeforePublishingTheActiveProjectSnapshot() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        Path home = root.resolve("home");
        Path javaRun = home.resolve("runs").resolve(runId.toString()).resolve("scip-java");
        Path javaIndex = javaRun.resolve("index.scip");
        Path javaWorkspace = javaRun.resolve("workspace");
        Files.createDirectories(javaWorkspace);
        Files.writeString(javaWorkspace.resolve("source.java"), "class Source {}");
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

        ScipProjectSnapshotLifecycle lifecycle = new ScipProjectSnapshotLifecycle(home);
        FileSymbolSnapshotStore active = new FileSymbolSnapshotStore(home.resolve("symbol-snapshots"));

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
        assertFalse(Files.exists(javaWorkspace),
                "provider source workspace must be removed after artifact normalization");
        assertTrue(Files.isDirectory(home.resolve("staged-snapshots").resolve(runId.toString())));

        lifecycle.promote(projectId, runId, stagedId);
        var snapshot = active.loadActiveKnowledge(projectId).orElseThrow();

        assertEquals(stagedId, snapshot.snapshotId());
        assertEquals(2, snapshot.symbols().size());
        assertTrue(snapshot.symbols().stream().anyMatch(symbol -> "JavaService".equals(symbol.name())));
        assertTrue(snapshot.symbols().stream().anyMatch(symbol -> "TsService".equals(symbol.name())));
        assertFalse(Files.exists(home.resolve("staged-snapshots").resolve(runId.toString())),
                "staged snapshot tree must be removed after successful promotion");
    }

    @Test
    void mergesSameQualifiedNameFromTwoScopesWithoutPathOrIdentityCollision() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        Path appIndex = root.resolve("app.scip");
        Path libIndex = root.resolve("lib.scip");
        writeIndex(
                appIndex,
                "typescript",
                "src/Shared.ts",
                "scip-typescript npm app 1.0.0 src/`Shared.ts`/Shared#",
                "Shared"
        );
        writeIndex(
                libIndex,
                "typescript",
                "src/Shared.ts",
                "scip-typescript npm lib 1.0.0 src/`Shared.ts`/Shared#",
                "Shared"
        );

        ScipProjectSnapshotLifecycle lifecycle = new ScipProjectSnapshotLifecycle(root.resolve("scoped-home"));
        FileSymbolSnapshotStore active = new FileSymbolSnapshotStore(root.resolve("scoped-home/symbol-snapshots"));
        String stagedId = lifecycle.stage(new IndexSnapshotStageRequest(
                runId,
                projectId,
                List.of(
                        new IndexingArtifact(Language.TYPESCRIPT, "scip-typescript", appIndex, Path.of("ui/app")),
                        new IndexingArtifact(Language.TYPESCRIPT, "scip-typescript", libIndex, Path.of("ui/lib"))
                )
        ));
        lifecycle.promote(projectId, runId, stagedId);

        var symbols = active.loadActiveKnowledge(projectId).orElseThrow().symbols();
        assertEquals(2, symbols.size());
        assertEquals(List.of("Shared", "Shared"), symbols.stream().map(symbol -> symbol.name()).sorted().toList());
        assertEquals(2, symbols.stream().map(symbol -> symbol.id()).distinct().count());
        assertNotEquals(symbols.get(0).fileId(), symbols.get(1).fileId());
        assertTrue(symbols.stream().anyMatch(symbol -> "ui/app/src/Shared.ts".equals(symbol.fileId())));
        assertTrue(symbols.stream().anyMatch(symbol -> "ui/lib/src/Shared.ts".equals(symbol.fileId())));
    }

    private static void writeIndex(
            Path file,
            String language,
            String relativePath,
            String rawSymbol,
            String displayName
    ) throws Exception {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
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
