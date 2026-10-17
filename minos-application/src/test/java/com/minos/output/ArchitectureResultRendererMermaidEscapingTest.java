package com.minos.output;

import com.minos.architecture.ArchitectureCentralityService;
import com.minos.architecture.ArchitectureConcentrationService;
import com.minos.architecture.ArchitectureDependencyGraph;
import com.minos.architecture.ArchitectureDependencyService;
import com.minos.architecture.ArchitectureIntelligenceService;
import com.minos.architecture.ArchitectureIntelligenceView;
import com.minos.architecture.ArchitectureOverview;
import com.minos.architecture.ArchitectureTechnologyService;
import com.minos.architecture.ArchitectureTopologyService;
import com.minos.discovery.ProjectDiscovery;
import com.minos.discovery.ProjectDiscovery.BuildSystem;
import com.minos.discovery.ProjectDiscovery.DiscoveredModule;
import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.discovery.ProjectDiscovery.SourceRoot;
import com.minos.discovery.ProjectDiscovery.SourceRootKind;
import com.minos.domain.CodeEntityRef;
import com.minos.domain.CodeEntityType;
import com.minos.domain.Evidence;
import com.minos.domain.EvidenceType;
import com.minos.domain.InformationNature;
import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.Relationship;
import com.minos.domain.RelationshipKind;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.store.CodeKnowledgeSnapshot;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MINOS-AUD-SEC-08 : le rendu Mermaid du graphe d'architecture n'émet aucun chevron ni directive issus du dépôt analysé.
 * Le saut de ligne {@code <br/>} reste le seul balisage, inséré par MINOS après l'échappement.
 */
class ArchitectureResultRendererMermaidEscapingTest {
    private static final Origin ORIGIN = new Origin("test", "TEST", "1", "run", OriginType.OTHER);
    private static final String NOMINAL_PROJECT = "multi-module";
    private static final String SNAPSHOT = "snapshot-sec08-render";

    @Test
    void aHostileModuleNameReachesTheOutputWithoutAnyChevron() {
        String mermaid = mermaid(NOMINAL_PROJECT, "<img src=x onerror=alert(1)>");

        assertFalse(mermaid.contains("<img"), mermaid);
        assertTrue(mermaid.contains("m0[\"&lt;img src=x onerror=alert(1)&gt;<br/>api\"]"), mermaid);
    }

    @Test
    void anAmpersandIsEscapedOnceAndTheChevronBesideIt() {
        String mermaid = mermaid(NOMINAL_PROJECT, "a&b<c");

        assertTrue(mermaid.contains("[\"a&amp;b&lt;c<br/>api\"]"), mermaid);
        assertFalse(mermaid.contains("&amp;amp;"), mermaid);
        assertFalse(mermaid.contains("&amp;lt;"), mermaid);
    }

    @Test
    void aNominalModuleKeepsItsNameAndItsPathOnTwoLines() {
        String mermaid = mermaid(NOMINAL_PROJECT, "core");

        assertTrue(mermaid.contains("m0[\"core<br/>api\"]"), mermaid);
        assertTrue(mermaid.contains("m1[\"app<br/>app\"]"), mermaid);
        assertEquals(2, mermaid.split("<br/>", -1).length - 1, "one line break per labelled module: " + mermaid);
    }

    @Test
    void aModuleWithoutRelativePathIsLabelledByItsEscapedNameAlone() {
        String mermaid = mermaid(NOMINAL_PROJECT, "root<x>", "");

        assertTrue(mermaid.contains("[\"root&lt;x&gt;\"]"), mermaid);
        assertFalse(mermaid.contains("root&lt;x&gt;<br/>"), mermaid);
    }

    @Test
    void theOnlyMarkupLeftInTheOutputIsTheLineBreakOfMinos() {
        String mermaid = mermaid(NOMINAL_PROJECT, "<script>alert(1)</script>");

        String labels = mermaid.lines().filter(line -> line.contains("[\"")).reduce("", String::concat);

        assertEquals("", labels.replace("<br/>", "").replaceAll("[^<>]", ""), mermaid);
    }

    @Test
    void aProjectNameCannotOpenAMermaidDirective() {
        String mermaid = mermaid("demo %%{init: {\"theme\":\"dark\"}}%%", "core");

        assertFalse(mermaid.contains("%%{"), mermaid);
        assertEquals(1, mermaid.lines().filter(line -> line.startsWith("  %% MINOS project: ")).count(),
                "the comment stays on a single line: " + mermaid);
    }

    @Test
    void aNominalProjectNameKeepsItsCommentLineUnchanged() {
        String mermaid = mermaid(NOMINAL_PROJECT, "core");

        assertTrue(mermaid.contains("\n  %% MINOS project: " + NOMINAL_PROJECT + " | snapshot: " + SNAPSHOT + "\n"),
                mermaid);
    }

    private static String mermaid(String projectName, String apiModuleName) {
        return mermaid(projectName, apiModuleName, "api");
    }

    private static String mermaid(String projectName, String apiModuleName, String apiModulePath) {
        return ArchitectureResultRenderer.renderGraph(view(projectName, apiModuleName, apiModulePath), null,
                ArchitectureResultRenderer.GraphFormat.MERMAID);
    }

    private static ArchitectureIntelligenceView view(String projectName, String apiModuleName, String apiModulePath) {
        UUID projectId = UUID.fromString("00000000-0000-0000-0000-000000000508");
        Symbol api = symbol(projectId, "api", "api/src/main/java/com/acme/api/Api.java");
        Symbol app = symbol(projectId, "app", "app/src/main/java/com/acme/app/App.java");
        CodeKnowledgeSnapshot snapshot = new CodeKnowledgeSnapshot(projectId, SNAPSHOT, List.of(api, app),
                List.of(), List.of(dependency(projectId, app, api)));
        ProjectDiscovery discovery = new ProjectDiscovery(Path.of("."), projectName, Set.of(Language.JAVA),
                Set.of(BuildSystem.MAVEN), List.of(
                        module(apiModulePath, apiModuleName, "api/src/main/java"), module("app", "app", "app/src/main/java")));

        ArchitectureOverview overview = new ArchitectureTopologyService().build(discovery, snapshot);
        ArchitectureDependencyGraph dependencies = new ArchitectureDependencyService().build(discovery, snapshot);
        var concentration = new ArchitectureConcentrationService().analyze(overview, dependencies);
        return new ArchitectureIntelligenceService().compose(overview, dependencies, concentration,
                new ArchitectureCentralityService().rank(concentration),
                new ArchitectureTechnologyService().detect(discovery, overview));
    }

    private static DiscoveredModule module(String path, String name, String sourceRoot) {
        return new DiscoveredModule(Path.of(path), name, Set.of(BuildSystem.MAVEN),
                List.of(new SourceRoot(Path.of(sourceRoot), SourceRootKind.SOURCE, Language.JAVA)));
    }

    private static Symbol symbol(UUID projectId, String id, String fileId) {
        return new Symbol("sym:" + id, "key:" + id, SymbolIdentityQuality.STRUCTURAL_FALLBACK, projectId.toString(), null,
                fileId, null, SymbolKind.CLASS, id, "com.acme." + id, null, "java", null, ResolutionStatus.RESOLVED,
                ORIGIN, false, false, Set.of());
    }

    private static Relationship dependency(UUID projectId, Symbol source, Symbol target) {
        return new Relationship("rel:d1", projectId.toString(), ref(source), ref(target), null,
                RelationshipKind.DEPENDS_ON, null, ResolutionStatus.RESOLVED, InformationNature.DERIVED, 1.0, ORIGIN,
                List.of(new Evidence(EvidenceType.DERIVATION_PATH, "dependency for test", ref(source), ref(target),
                        null, 1.0)));
    }

    private static CodeEntityRef ref(Symbol symbol) {
        return new CodeEntityRef(CodeEntityType.SYMBOL, symbol.id());
    }
}
