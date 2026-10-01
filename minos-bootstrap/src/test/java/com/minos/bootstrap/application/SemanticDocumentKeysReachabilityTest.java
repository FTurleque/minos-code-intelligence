package com.minos.bootstrap.application;

import com.minos.adapter.scip.runtime.ScipProjectSnapshotLifecycle;
import com.minos.application.semantic.SemanticDocumentFactory;
import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.domain.Symbol;
import com.minos.orchestration.IndexingRuntimePorts.IndexSnapshotStageRequest;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.registry.RegisteredProject;
import com.minos.semantic.SemanticDocument;
import com.minos.storage.local.registry.LocalProjectRegistry;
import com.minos.storage.local.store.FileSymbolSnapshotStore;
import com.minos.store.CodeKnowledgeSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.scip_code.scip.Document;
import org.scip_code.scip.Index;
import org.scip_code.scip.Occurrence;
import org.scip_code.scip.SingleLineRange;
import org.scip_code.scip.SymbolInformation;
import org.scip_code.scip.SymbolRole;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Q9 (PLAUSIBLE, non reproduit) : le constat dit qu'une clé sémantique en double fait échouer toute l'indexation
 * sémantique ({@code SemanticDocumentFactory} lève {@code IllegalStateException}). Ces tests prouvent, par le
 * chemin de production (index SCIP, mise en snapshot du run, promotion, chargement du snapshot actif, fabrique),
 * que deux symboles de même clé ne peuvent pas arriver jusqu'à la fabrique : le producteur de symboles dérive
 * l'identifiant de la clé, et chaque étape en amont fusionne ou refuse un identifiant en double. Le constat tombe,
 * aucun correctif n'est écrit (FIAB-SUIVI § 11).
 */
class SemanticDocumentKeysReachabilityTest {

    private static final String SHARED_PATH = "src/Shared.ts";
    private static final String TYPESCRIPT = "typescript";
    private static final String SHARED_NAME = "Shared";
    private static final String APP_SYMBOL = "scip-typescript npm app 1.0.0 src/`Shared.ts`/Shared#";
    private static final String LIB_SYMBOL = "scip-typescript npm lib 1.0.0 src/`Shared.ts`/Shared#";
    private static final String APP_SCOPE = "ui/app";
    private static final String PROVIDER = "scip-typescript";

    @TempDir
    Path temp;

    private UUID projectId;

    private CodeKnowledgeSnapshot snapshotOf(List<IndexingArtifact> artifacts) throws Exception {
        Path home = Files.createDirectories(temp.resolve("home"));
        LocalProjectRegistry registry = new LocalProjectRegistry(home.resolve("registry"));
        RegisteredProject project = registry.registerProject(Files.createDirectories(temp.resolve("project")), "q9");
        projectId = project.id();
        UUID runId = UUID.randomUUID();
        ScipProjectSnapshotLifecycle lifecycle = new ScipProjectSnapshotLifecycle(home);
        String staged = lifecycle.stage(new IndexSnapshotStageRequest(runId, projectId, artifacts));
        lifecycle.promote(projectId, runId, staged);
        return new FileSymbolSnapshotStore(home.resolve("symbol-snapshots")).loadActiveKnowledge(projectId).orElseThrow();
    }

    private List<SemanticDocument> semanticDocumentsOf(CodeKnowledgeSnapshot snapshot) throws IOException {
        RegisteredProject project = new LocalProjectRegistry(temp.resolve("home").resolve("registry"))
                .findProject(projectId).orElseThrow();
        return new SemanticDocumentFactory().build(project, snapshot);
    }

    private static void assertKeysAreUnique(List<SemanticDocument> documents) {
        assertEquals(documents.size(), documents.stream().map(SemanticDocument::stableKey).distinct().count(),
                "every semantic document has its own stable key");
    }

    @Test
    void theSameQualifiedNameInTwoScopesGivesTwoSymbolsAndTwoSetsOfUniqueSemanticKeys() throws Exception {
        Path appIndex = writeIndex(temp.resolve("app.scip"), List.of(APP_SYMBOL, APP_SYMBOL));
        Path libIndex = writeIndex(temp.resolve("lib.scip"), List.of(LIB_SYMBOL));

        CodeKnowledgeSnapshot snapshot = snapshotOf(List.of(
                new IndexingArtifact(Language.TYPESCRIPT, PROVIDER, appIndex, Path.of(APP_SCOPE)),
                new IndexingArtifact(Language.TYPESCRIPT, PROVIDER, libIndex, Path.of("ui/lib"))));

        assertEquals(2, snapshot.symbols().size(), "one symbol per scope: the duplicate definition in app is merged");
        assertEquals(2, snapshot.symbols().stream().map(Symbol::symbolKey).distinct().count());
        List<SemanticDocument> documents = semanticDocumentsOf(snapshot);
        assertKeysAreUnique(documents);
        assertTrue(documents.size() >= 2, "the factory produced documents for both symbols");
    }

    @Test
    void theSameSymbolDefinedTwiceInOneIndexIsMergedBeforeItReachesTheFactory() throws Exception {
        Path index = writeIndex(temp.resolve("twice.scip"), List.of(APP_SYMBOL, APP_SYMBOL, APP_SYMBOL));

        CodeKnowledgeSnapshot snapshot = snapshotOf(List.of(new IndexingArtifact(Language.TYPESCRIPT, PROVIDER, index)));

        assertEquals(1, snapshot.symbols().size());
        assertKeysAreUnique(semanticDocumentsOf(snapshot));
    }

    @Test
    void twoProvidersDescribingTheSameSymbolAreRefusedWhileStagingNotMergedIntoADuplicate() throws Exception {
        Path first = writeIndex(temp.resolve("first.scip"), List.of(APP_SYMBOL));
        Path second = writeIndex(temp.resolve("second.scip"), List.of(APP_SYMBOL));
        List<IndexingArtifact> artifacts = List.of(
                new IndexingArtifact(Language.TYPESCRIPT, PROVIDER, first, Path.of(APP_SCOPE)),
                new IndexingArtifact(Language.JAVA, "scip-java", second, Path.of(APP_SCOPE)));

        IllegalStateException refusal = assertThrows(IllegalStateException.class, () -> snapshotOf(artifacts));

        assertTrue(refusal.getMessage().contains("collision"), refusal.getMessage());
    }

    /** Un index SCIP d'un seul document dont chaque symbole de {@code rawSymbols} est défini (les doublons sont voulus). */
    private static Path writeIndex(Path file, List<String> rawSymbols) throws IOException {
        Document.Builder document = Document.newBuilder()
                .setLanguage(TYPESCRIPT)
                .setRelativePath(SHARED_PATH)
                .setPositionEncoding(org.scip_code.scip.PositionEncoding.UTF16CodeUnitOffsetFromLineStart);
        for (String rawSymbol : rawSymbols) {
            document.addSymbols(SymbolInformation.newBuilder()
                    .setSymbol(rawSymbol)
                    .setDisplayName(SHARED_NAME)
                    .setKind(SymbolInformation.Kind.Class));
            document.addOccurrences(Occurrence.newBuilder()
                    .setSymbol(rawSymbol)
                    .setSymbolRoles(SymbolRole.Definition_VALUE)
                    .setSingleLineRange(SingleLineRange.newBuilder()
                            .setLine(0).setStartCharacter(0).setEndCharacter(SHARED_NAME.length())));
        }
        try (OutputStream output = Files.newOutputStream(file)) {
            Index.newBuilder().addDocuments(document).build().writeTo(output);
        }
        return file;
    }
}
