package com.minos.impact;

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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Snapshot pseudo-aléatoire à graine fixe et rendu canonique des rapports d'impact (AUD-PERF-11, tâche 1.1).
 *
 * <p>La référence ({@code reference-reports.txt}, une empreinte par rapport) a été produite au HEAD {@code 9e4a6e61}, avant que l'index des
 * arêtes entrantes ne quitte {@link ImpactAnalysisService} : tout chemin d'analyse doit la rendre à l'identique,
 * ordre compris. Le snapshot mêle toutes les sortes de relations, les quatre statuts de résolution, les trois
 * natures, des extrémités fichier, des cibles inconnues ou non résolues, des symboles externes et générés, des
 * confiances égales (départage par signature de chemin) et une liste d'entrée non triée.</p>
 */
public final class ImpactReferenceFixture {

    public static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-00000000a011");
    public static final String SNAPSHOT_ID = "impact-reference";
    public static final int SYMBOLS = 48;
    /** {@code s00} à {@code s39} : locaux ; {@code s40} à {@code s43} : externes ; {@code s44} à {@code s47} : générés. */
    public static final int LOCAL_SYMBOLS = 40;
    private static final int RELATIONSHIPS = 360;
    private static final long SEED = 20_261_014L;
    private static final double[] CONFIDENCES = {0.5, 0.6, 0.7, 0.82, 0.9, 1.0};
    private static final Origin FACTUAL = new Origin("fixture", "TEST", "1", "run-1", OriginType.OTHER);
    private static final Origin DERIVED = new Origin("minos", "M8_TEST", "1", "run-1", OriginType.DERIVED_BY_MINOS);
    private static final List<Shape> SHAPES = List.of(
            new Shape(1, 50), new Shape(3, 7),
            new Shape(8, 200));

    private ImpactReferenceFixture() {
    }

    public static CodeKnowledgeSnapshot snapshot() {
        Random random = new Random(SEED);
        List<Symbol> symbols = new ArrayList<>(SYMBOLS);
        for (int index = 0; index < SYMBOLS; index++) {
            symbols.add(symbol(symbolId(index), index >= LOCAL_SYMBOLS && index < 44, index >= 44));
        }
        RelationshipKind[] kinds = RelationshipKind.values();
        List<Relationship> relationships = new ArrayList<>(RELATIONSHIPS);
        for (int index = 0; index < RELATIONSHIPS; index++) {
            String id = String.format(Locale.ROOT, "r%03d", (index * 7919) % 1000);
            RelationshipKind kind = random.nextInt(4) == 0
                    ? kinds[random.nextInt(kinds.length)]
                    : List.of(RelationshipKind.CALLS, RelationshipKind.REFERENCES, RelationshipKind.DEPENDS_ON,
                    RelationshipKind.RELATED_TEST, RelationshipKind.IMPLEMENTS).get(random.nextInt(5));
            CodeEntityRef source = random.nextInt(10) == 0
                    ? new CodeEntityRef(CodeEntityType.SOURCE_FILE, "file-" + random.nextInt(4))
                    : new CodeEntityRef(CodeEntityType.SYMBOL, symbolId(random.nextInt(SYMBOLS)));
            int targetChoice = random.nextInt(20);
            CodeEntityRef target;
            if (targetChoice == 0) {
                target = null;
            } else if (targetChoice == 1) {
                target = new CodeEntityRef(CodeEntityType.SOURCE_FILE, "file-" + random.nextInt(4));
            } else if (targetChoice == 2) {
                target = new CodeEntityRef(CodeEntityType.SYMBOL, "ghost-" + random.nextInt(3));
            } else {
                target = new CodeEntityRef(CodeEntityType.SYMBOL, symbolId(random.nextInt(SYMBOLS)));
            }
            relationships.add(relationship(id, source, target, kind, random));
        }
        Collections.shuffle(relationships, random);
        return new CodeKnowledgeSnapshot(PROJECT_ID, SNAPSHOT_ID, symbols, List.of(), relationships);
    }

    /** Les requêtes de la référence : chaque symbole local, trois formes de bornes. */
    public static List<ImpactAnalysisRequest> requests() {
        List<ImpactAnalysisRequest> requests = new ArrayList<>();
        for (int index = 0; index < LOCAL_SYMBOLS; index++) {
            for (Shape shape : SHAPES) {
                requests.add(new ImpactAnalysisRequest(symbolId(index), shape.maxDepth(), shape.maxResults()));
            }
        }
        return List.copyOf(requests);
    }

    public static String symbolId(int index) {
        return String.format(Locale.ROOT, "s%02d", index);
    }

    /** Rendu canonique : tout ce qui est observable dans le rapport, dans l'ordre du rapport. */
    public static String render(ImpactAnalysisReport report) {
        StringBuilder text = new StringBuilder();
        text.append("# root=").append(report.rootSymbol().id())
                .append(" depth=").append(report.request().maxDepth())
                .append(" results=").append(report.request().maxResults())
                .append(" snapshot=").append(report.snapshotId()).append('\n');
        text.append("limitations=").append(report.limitations()).append('\n');
        report.impacts().forEach(impact -> text.append("I ").append(render(impact)).append('\n'));
        report.potentiallyImpactedTests().forEach(impact -> text.append("T ").append(render(impact)).append('\n'));
        return text.toString();
    }

    /**
     * Une ligne par rapport : la requête et l'empreinte SHA-256 de son rendu canonique. La référence reste petite
     * et un écart désigne la requête fautive.
     */
    public static String digestLine(ImpactAnalysisReport report) {
        return report.request().symbolId() + ' ' + report.request().maxDepth() + ' ' + report.request().maxResults()
                + ' ' + sha256(render(report)) + '\n';
    }

    public static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public static String reference(String resource) throws IOException {
        try (InputStream input = ImpactReferenceFixture.class.getResourceAsStream(resource)) {
            Objects.requireNonNull(input, resource);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }

    private static String render(ImpactedSymbol impact) {
        return impact.symbol().id() + ' ' + impact.level() + " d=" + impact.depth() + " c=" + impact.confidence()
                + " test=" + impact.testImpact() + " path=" + impact.path().stream()
                .map(step -> step.changedSymbolId() + "<" + step.relationshipId() + ":" + step.relationshipKind()
                        + ":" + step.relationshipNature() + ":" + step.confidence() + "<" + step.impactedSymbolId())
                .collect(Collectors.joining(","));
    }

    private static Relationship relationship(
            String id, CodeEntityRef source, CodeEntityRef target, RelationshipKind kind, Random random) {
        if (target == null) {
            return new Relationship(id, PROJECT_ID.toString(), source, null, "unresolved-" + id, kind, null,
                    ResolutionStatus.UNRESOLVED, InformationNature.FACTUAL, null, FACTUAL, List.of());
        }
        int statusChoice = random.nextInt(20);
        ResolutionStatus status = statusChoice < 14 ? ResolutionStatus.RESOLVED
                : statusChoice < 17 ? ResolutionStatus.HEURISTIC : ResolutionStatus.PARTIALLY_RESOLVED;
        int natureChoice = random.nextInt(10);
        double confidence = CONFIDENCES[random.nextInt(CONFIDENCES.length)];
        if (natureChoice < 5) {
            Double factualConfidence = random.nextBoolean() ? null : confidence;
            return new Relationship(id, PROJECT_ID.toString(), source, target, null, kind, null, status,
                    InformationNature.FACTUAL, factualConfidence, FACTUAL, List.of());
        }
        InformationNature nature = natureChoice < 8 ? InformationNature.DERIVED : InformationNature.HEURISTIC;
        return new Relationship(id, PROJECT_ID.toString(), source, target, null, kind, null, status, nature,
                confidence, DERIVED, List.of(new Evidence(EvidenceType.DERIVATION_PATH, "path " + id, source, target,
                null, confidence)));
    }

    private record Shape(int maxDepth, int maxResults) {
    }

    private static Symbol symbol(String id, boolean external, boolean generated) {
        return new Symbol(id, "key:" + id, SymbolIdentityQuality.STRUCTURAL_FALLBACK, PROJECT_ID.toString(), null,
                "src/main/java/" + id + ".java", null, SymbolKind.CLASS, id, "com.acme." + id, null, "java", null,
                ResolutionStatus.RESOLVED, FACTUAL, external, generated, Set.of());
    }
}
