package com.minos.output;

import com.minos.context.CodeContextResult;
import com.minos.context.CodeSearchResponse;
import com.minos.context.ContextRelationshipResult;
import com.minos.context.SourceExcerpt;
import com.minos.domain.CodeEntityRef;
import com.minos.domain.CodeEntityType;
import com.minos.domain.Evidence;
import com.minos.domain.EvidenceType;
import com.minos.domain.InformationNature;
import com.minos.domain.OccurrenceRole;
import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.PositionEncoding;
import com.minos.domain.RelationshipDirection;
import com.minos.domain.RelationshipKind;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.domain.SymbolLocation;
import com.minos.query.RelationshipResult;
import com.minos.query.SymbolResult;
import com.minos.query.UsageResult;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Q13 — caractérise, octet pour octet, le JSON des trois renderers qui l'écrivaient à la main
 * ({@link CodeSearchRenderer}, {@link CodeIntelligenceResultRenderer}, {@link SymbolResultRenderer}) : les
 * références ({@code src/test/resources/com/minos/output/renderer-json/}) ont été produites par le code d'origine,
 * avant leur migration vers {@link DeterministicJson}, et ne doivent plus bouger. Les fixtures couvrent chaque
 * branche : valeurs absentes, listes vides et pleines, rôles triés, preuves sans source ni cible, confiance
 * absente, caractères à échapper, virgules entre éléments.
 *
 * <p>Une référence n'est réécrite que sur demande explicite ({@code -Dminos.renderer.write=true}).</p>
 */
class RendererJsonCharacterizationTest {

    private static final String WRITE_PROPERTY = "minos.renderer.write";
    private static final Path SOURCE_DIRECTORY = Path.of(
            "minos-application", "src", "test", "resources", "com", "minos", "output", "renderer-json");

    // --- CodeSearchRenderer ---------------------------------------------------------------------

    @Test
    void searchResponseWithEveryBranch() throws IOException {
        CodeSearchResponse response = new CodeSearchResponse(
                "project-1", "Service \"main\"", 3, 512, 320, 90, true,
                List.of(fullContext(), bareContext()));

        assertMatches("code-search-full.json", CodeSearchRenderer.render(response, SymbolOutputFormat.JSON));
    }

    @Test
    void searchResponseWithoutQueryAndWithoutContexts() throws IOException {
        CodeSearchResponse response = new CodeSearchResponse(
                "project-1", null, 0, 512, 0, 0, false, List.of());

        assertMatches("code-search-empty.json", CodeSearchRenderer.render(response, SymbolOutputFormat.JSON));
    }

    @Test
    void sourceExcerptAlone() throws IOException {
        SourceExcerpt source = new SourceExcerpt(
                "src/été/Test.java", 2, 9, "class \"A\" {\n\t// \u0001   \uD800 é\n}\\",
                false, true, 12, 40, 300);

        assertMatches("source-excerpt.json", CodeSearchRenderer.renderSource(source, SymbolOutputFormat.JSON));
    }

    // --- CodeIntelligenceResultRenderer ---------------------------------------------------------

    @Test
    void usagesWithAndWithoutRoles() throws IOException {
        List<UsageResult> usages = List.of(
                new UsageResult("occ-1", "project-1", "symbol-1", location(),
                        EnumSet.of(OccurrenceRole.READ, OccurrenceRole.CALL, OccurrenceRole.DEFINITION),
                        ResolutionStatus.RESOLVED, origin()),
                new UsageResult("occ-2", "project-1", "symbol-1",
                        new SymbolLocation("src/Other.java", 1, 0, 1, 3, PositionEncoding.UTF16_CODE_UNITS),
                        Set.of(), ResolutionStatus.UNRESOLVED, origin()));

        assertMatches("usages.json", CodeIntelligenceResultRenderer.renderUsages(usages, SymbolOutputFormat.JSON));
        assertMatches("usages-empty.json",
                CodeIntelligenceResultRenderer.renderUsages(List.of(), SymbolOutputFormat.JSON));
    }

    @Test
    void relationshipsWithEveryBranch() throws IOException {
        assertMatches("relationships.json", CodeIntelligenceResultRenderer.renderRelationships(
                List.of(resolvedRelationship(), unresolvedRelationship()), SymbolOutputFormat.JSON));
        assertMatches("relationships-empty.json",
                CodeIntelligenceResultRenderer.renderRelationships(List.of(), SymbolOutputFormat.JSON));
    }

    // --- SymbolResultRenderer -------------------------------------------------------------------

    @Test
    void symbolsWithAndWithoutOptionalFields() throws IOException {
        assertMatches("symbols.json", SymbolResultRenderer.render(
                List.of(fullSymbol(), bareSymbol()), SymbolOutputFormat.JSON));
    }

    // --- fixtures ---------------------------------------------------------------------------------

    private static CodeContextResult fullContext() {
        SourceExcerpt source = new SourceExcerpt(
                "src/Service.java", 10, 12, "class Service {\n}", false, false, 9, 100, 600);
        return new CodeContextResult(
                fullSymbol(), source,
                List.of(new ContextRelationshipResult(1, entity("anchor"), RelationshipDirection.OUTGOING,
                                resolvedRelationship()),
                        new ContextRelationshipResult(2, entity("anchor"), RelationshipDirection.INCOMING,
                                unresolvedRelationship())),
                List.of(new UsageResult("occ-1", "project-1", "symbol-1", location(),
                                EnumSet.of(OccurrenceRole.REFERENCE, OccurrenceRole.CALL),
                                ResolutionStatus.RESOLVED, origin()),
                        new UsageResult("occ-2", "project-1", "symbol-1", location(),
                                EnumSet.of(OccurrenceRole.IMPORT), ResolutionStatus.RESOLVED, origin())),
                200, true);
    }

    private static CodeContextResult bareContext() {
        return new CodeContextResult(bareSymbol(), null, List.of(), List.of(), 5, false);
    }

    private static RelationshipResult resolvedRelationship() {
        return new RelationshipResult(
                "rel-1", "project-1", entity("source"), entity("target"), null,
                RelationshipKind.DEPENDS_ON, location(), ResolutionStatus.RESOLVED,
                InformationNature.DERIVED, 0.8, origin(),
                List.of(new Evidence(EvidenceType.DERIVATION_PATH, "path-\uD800-proof \"q\"",
                                entity("source"), entity("target"), location(), 0.8),
                        new Evidence(EvidenceType.NAMING_CONVENTION, "bare evidence", null, null, null, null)));
    }

    private static RelationshipResult unresolvedRelationship() {
        return new RelationshipResult(
                "rel-2", "project-1", entity("source"), null, "com.acme.Missing",
                RelationshipKind.CALLS, null, ResolutionStatus.UNRESOLVED,
                InformationNature.FACTUAL, null, origin(), List.of());
    }

    private static SymbolResult fullSymbol() {
        return new SymbolResult(
                "sym-1", "key-1", SymbolIdentityQuality.CANONICAL, "project-1", "module-main",
                "src/Service.java", SymbolKind.CLASS, "Service", "com.acme.Service",
                "class Service<T>", "java", location(), ResolutionStatus.RESOLVED, origin(), false, true);
    }

    private static SymbolResult bareSymbol() {
        return new SymbolResult(
                "sym-2", "key-2", SymbolIdentityQuality.PROVIDER_SCOPED_FALLBACK, "project-1", null, null,
                SymbolKind.METHOD, "run", null, null, "java", null, ResolutionStatus.RESOLVED,
                new Origin("scip-java", "SCIP_INDEXER", null, null, OriginType.SCIP), true, false);
    }

    private static CodeEntityRef entity(String id) {
        return new CodeEntityRef(CodeEntityType.SYMBOL, id);
    }

    private static SymbolLocation location() {
        return new SymbolLocation("src/Test.java", 4, 2, 5, 10, PositionEncoding.UTF16_CODE_UNITS);
    }

    private static Origin origin() {
        return new Origin("fixture-provider", "TEST", "1", "run-1", OriginType.OTHER);
    }

    // --- references -------------------------------------------------------------------------------

    private static void assertMatches(String name, String actual) throws IOException {
        if (Boolean.getBoolean(WRITE_PROPERTY)) {
            Files.createDirectories(SOURCE_DIRECTORY);
            Files.writeString(SOURCE_DIRECTORY.resolve(name), actual, StandardCharsets.UTF_8);
            return;
        }
        String resource = "/com/minos/output/renderer-json/" + name;
        try (InputStream input = RendererJsonCharacterizationTest.class.getResourceAsStream(resource)) {
            if (input == null) {
                fail("reference missing: " + name + " (generate it once with -D" + WRITE_PROPERTY + "=true)");
            }
            assertEquals(new String(input.readAllBytes(), StandardCharsets.UTF_8), actual, name);
        }
    }
}
