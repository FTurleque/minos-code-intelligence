package com.minos.adapter.scip.runtime;

import com.minos.discovery.ModuleAssignmentRule;
import com.minos.discovery.ProjectDiscovery;
import com.minos.discovery.ProjectDiscovery.BuildSystem;
import com.minos.discovery.ProjectDiscovery.DiscoveredModule;
import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.discovery.ProjectDiscovery.SourceRoot;
import com.minos.discovery.ProjectDiscovery.SourceRootKind;
import com.minos.orchestration.IndexingRuntimePorts.IndexSnapshotStageRequest;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.storage.local.store.FileSymbolSnapshotStore;
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
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    /**
     * MINOS-AUD-F04 : une seule portée à la racine couvre deux modules (réacteur) ; chaque symbole reçoit le module de
     * son fichier, avec l'identifiant que l'architecture affiche. Les fichiers hors module restent sans module.
     */
    @Test
    void stagingFillsTheModuleOfEachSymbolFromItsFileInAMultiModuleScope() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        Path index = root.resolve("multi.scip");
        writeMultiDocumentIndex(index, List.of(
                new Doc("packages/api/src/Greeting.ts", "scip-typescript npm api 1.0.0 src/`Greeting.ts`/Greeting#", "Greeting"),
                new Doc("packages/web/src/App.ts", "scip-typescript npm web 1.0.0 src/`App.ts`/App#", "App"),
                new Doc("tools/Script.ts", "scip-typescript npm tools 1.0.0 src/`Script.ts`/Script#", "Script")));
        ProjectDiscovery discovery = new ProjectDiscovery(root, "multi", Set.of(Language.TYPESCRIPT),
                Set.of(BuildSystem.NPM), List.of(
                new DiscoveredModule(Path.of("packages/api"), "api", Set.of(BuildSystem.NPM),
                        List.of(new SourceRoot(Path.of("packages/api/src"), SourceRootKind.SOURCE, Language.TYPESCRIPT))),
                new DiscoveredModule(Path.of("packages/web"), "web", Set.of(BuildSystem.NPM),
                        List.of(new SourceRoot(Path.of("packages/web/src"), SourceRootKind.SOURCE, Language.TYPESCRIPT)))));

        ScipProjectSnapshotLifecycle lifecycle = new ScipProjectSnapshotLifecycle(root.resolve("module-home"));
        FileSymbolSnapshotStore active = new FileSymbolSnapshotStore(root.resolve("module-home/symbol-snapshots"));
        String stagedId = lifecycle.stage(new IndexSnapshotStageRequest(
                runId, projectId,
                List.of(new IndexingArtifact(Language.TYPESCRIPT, "scip-typescript", index)),
                ModuleAssignmentRule.of(projectId.toString(), discovery)));
        lifecycle.promote(projectId, runId, stagedId);

        var symbols = active.loadActiveKnowledge(projectId).orElseThrow().symbols();
        assertEquals(3, symbols.size());
        assertEquals(ModuleAssignmentRule.moduleId(projectId.toString(), Path.of("packages/api")),
                symbolNamed(symbols, "Greeting").moduleId());
        assertEquals(ModuleAssignmentRule.moduleId(projectId.toString(), Path.of("packages/web")),
                symbolNamed(symbols, "App").moduleId());
        assertNull(symbolNamed(symbols, "Script").moduleId());
    }

    @Test
    void stagingWithoutModuleAssignmentLeavesEverySymbolWithoutModule() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        Path index = root.resolve("plain.scip");
        writeIndex(index, "typescript", "packages/api/src/Greeting.ts",
                "scip-typescript npm api 1.0.0 src/`Greeting.ts`/Greeting#", "Greeting");

        ScipProjectSnapshotLifecycle lifecycle = new ScipProjectSnapshotLifecycle(root.resolve("plain-home"));
        FileSymbolSnapshotStore active = new FileSymbolSnapshotStore(root.resolve("plain-home/symbol-snapshots"));
        String stagedId = lifecycle.stage(new IndexSnapshotStageRequest(runId, projectId,
                List.of(new IndexingArtifact(Language.TYPESCRIPT, "scip-typescript", index))));
        lifecycle.promote(projectId, runId, stagedId);

        assertNull(active.loadActiveKnowledge(projectId).orElseThrow().symbols().getFirst().moduleId());
    }

    private static com.minos.domain.Symbol symbolNamed(List<com.minos.domain.Symbol> symbols, String name) {
        return symbols.stream().filter(symbol -> name.equals(symbol.name())).findFirst().orElseThrow();
    }

    private record Doc(String relativePath, String rawSymbol, String displayName) {
    }

    private static void writeMultiDocumentIndex(Path file, List<Doc> docs) throws Exception {
        Index.Builder index = Index.newBuilder();
        for (Doc doc : docs) {
            SymbolInformation symbol = SymbolInformation.newBuilder()
                    .setSymbol(doc.rawSymbol())
                    .setDisplayName(doc.displayName())
                    .setKind(SymbolInformation.Kind.Class)
                    .build();
            Occurrence definition = Occurrence.newBuilder()
                    .setSymbol(doc.rawSymbol())
                    .setSymbolRoles(SymbolRole.Definition_VALUE)
                    .setSingleLineRange(SingleLineRange.newBuilder()
                            .setLine(0)
                            .setStartCharacter(0)
                            .setEndCharacter(doc.displayName().length()))
                    .build();
            index.addDocuments(Document.newBuilder()
                    .setLanguage("typescript")
                    .setRelativePath(doc.relativePath())
                    .setPositionEncoding(org.scip_code.scip.PositionEncoding.UTF16CodeUnitOffsetFromLineStart)
                    .addSymbols(symbol)
                    .addOccurrences(definition)
                    .build());
        }
        try (OutputStream output = Files.newOutputStream(file)) {
            index.build().writeTo(output);
        }
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
