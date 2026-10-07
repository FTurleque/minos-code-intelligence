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
 * MINOS-AUD-F01 : les limitations du graphe d'architecture sont rendues en JSON, en texte, en Mermaid et en DOT, après
 * les clés et lignes existantes, sans changer ni les nœuds ni les arêtes des graphes.
 */
class ArchitectureResultRendererLimitationsTest {
    private static final Origin ORIGIN = new Origin("test", "TEST", "1", "run", OriginType.OTHER);
    private static final String LIMITATION = "OCCURRENCE_REFERENCES_NOT_PROJECTED";

    @Test
    void jsonCarriesTheLimitationsAsTheLastKey() {
        String json = ArchitectureResultRenderer.render(view(true), SymbolOutputFormat.JSON);

        assertTrue(json.contains("\"limitations\":[\"" + LIMITATION + "\"]"), json);
        assertTrue(json.indexOf("\"moduleDependencies\"") < json.indexOf("\"limitations\""),
                "the new key comes after every existing key");
        assertTrue(json.endsWith("\"limitations\":[\"" + LIMITATION + "\"]}"), "and ends the object: " + json);
    }

    @Test
    void jsonKeepsAnEmptyListWhenNothingIsToBeDeclared() {
        String json = ArchitectureResultRenderer.render(view(false), SymbolOutputFormat.JSON);

        assertTrue(json.endsWith("\"limitations\":[]}"), json);
        assertFalse(json.contains(LIMITATION), json);
    }

    @Test
    void textCarriesALimitationsLineAfterTheExistingLines() {
        String withLimitation = ArchitectureResultRenderer.render(view(true), SymbolOutputFormat.TEXT);
        String without = ArchitectureResultRenderer.render(view(false), SymbolOutputFormat.TEXT);

        assertTrue(withLimitation.endsWith("\nlimitations: [" + LIMITATION + "]"), withLimitation);
        assertTrue(without.endsWith("\nlimitations: []"), without);
        assertEquals(without.lines().filter(line -> !line.startsWith("limitations:")).toList(),
                withLimitation.lines().filter(line -> !line.startsWith("limitations:")).toList(),
                "every other line is identical");
    }

    @Test
    void mermaidAndDotGetACommentAndKeepTheSameNodesAndEdges() {
        for (ArchitectureResultRenderer.GraphFormat format : ArchitectureResultRenderer.GraphFormat.values()) {
            String with = ArchitectureResultRenderer.renderGraph(view(true), null, format);
            String without = ArchitectureResultRenderer.renderGraph(view(false), null, format);

            String marker = format == ArchitectureResultRenderer.GraphFormat.MERMAID ? "%% limitation: " : "// limitation: ";
            assertTrue(with.contains(marker + LIMITATION), format + ": " + with);
            assertFalse(without.contains("limitation"), format + ": " + without);
            assertEquals(without.lines().toList(),
                    with.lines().filter(line -> !line.contains("limitation: ")).toList(),
                    format + ": same lines as without the limitation, besides the comment");
        }
    }

    private static ArchitectureIntelligenceView view(boolean withReferenceOccurrence) {
        UUID projectId = UUID.fromString("00000000-0000-0000-0000-000000000f03");
        Symbol api = symbol(projectId, "api", "api/src/main/java/com/acme/api/Api.java");
        Symbol app = symbol(projectId, "app", "app/src/main/java/com/acme/app/App.java");
        List<SymbolOccurrence> occurrences = withReferenceOccurrence
                ? List.of(new SymbolOccurrence("occurrence-1", projectId.toString(), new ResolvedSymbolReference(api.id()),
                        new SymbolLocation("app/src/main/java/com/acme/app/App.java", 5, 0, 5, 3,
                                PositionEncoding.UTF16_CODE_UNITS),
                        Set.of(OccurrenceRole.REFERENCE), ResolutionStatus.RESOLVED, ORIGIN, Set.of()))
                : List.of();
        CodeKnowledgeSnapshot snapshot = new CodeKnowledgeSnapshot(projectId, "snapshot-f01-render", List.of(api, app),
                occurrences, List.of(dependency(projectId, app, api)));
        ProjectDiscovery discovery = new ProjectDiscovery(Path.of("."), "multi-module", Set.of(Language.JAVA),
                Set.of(BuildSystem.MAVEN), List.of(
                        module("api", "api", "api/src/main/java"), module("app", "app", "app/src/main/java")));

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
