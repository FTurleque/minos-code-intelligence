package com.minos.bootstrap.semantic;

import com.minos.application.MinosApplication;
import com.minos.application.semantic.HybridSearchService;
import com.minos.domain.CodeEntityRef;
import com.minos.domain.CodeEntityType;
import com.minos.domain.InformationNature;
import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.PositionEncoding;
import com.minos.domain.Relationship;
import com.minos.domain.RelationshipKind;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.domain.SymbolLocation;
import com.minos.registry.RegisteredProject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * Corpus déterministe du test d'équivalence du classement hybride (lot A6, étape 4) et sérialisation exacte
 * d'un classement : clé stable, bits IEEE 754 de chaque score, mode, dans l'ordre rendu.
 *
 * <p>Le vocabulaire mêle ce que la normalisation traite différemment : casse, accents, ligatures, lettres dont
 * la minuscule change de longueur ({@code İ}), sigma grec, CJK, emoji (paires de substitution), chiffres
 * exotiques, soulignés, ponctuation. Les degrés de graphe sont répétés et beaucoup de documents partagent les
 * mêmes termes : les égalités exactes de score, départagées par {@code stableKey}, sont nombreuses.</p>
 *
 * <p>Certaines requêtes ne se trouvent dans le texte que comme sous-chaîne d'un terme ({@code napsho} dans
 * {@code snapshot}) : seul le bonus de phrase les classe, sans saturation par {@code clamp01}. Quelques requêtes
 * sont aussi classées en entier ({@link #FULL_LIMIT}), pas seulement sur leurs premiers résultats.</p>
 */
final class HybridRankingCorpus {

    static final String PROJECT = "hybrid-ranking";
    static final int LIMIT = 60;
    static final List<String> QUERIES = List.of(
            "snapshot store", "Snapshot_Store", "hybrid search", "normalize query", "publish pointer",
            "Été", "été naïve", "straße", "STRASSE", "İstanbul", "istanbul", "ΣΊΣΥΦΟΣ", "σίσυφος",
            "漢字", "検索 漢字", "😀", "x² ①", "Ⅻ", "user_id", "HTTP2", "v1.2.3", "foo-bar", "foo_bar",
            "ﬁle", "Ǆemo", "store store store", "a", "ab", "query-normalize", "method big0",
            "napsho", "tore", "ormaliz", "ashot_st", "ïve", "字検", "stanbul", "napsho tore");
    /** Requêtes classées en entier, jusqu'à la limite maximale du service. */
    static final List<String> FULL_QUERIES = List.of("store", "napsho");
    static final int FULL_LIMIT = HybridSearchService.MAX_RESULTS;

    private static final String[] WORDS = {
            "snapshot", "store", "Snapshot_Store", "hybrid", "search", "normalize", "query", "publish",
            "pointer", "Été", "ÉTÉ", "naïve", "NAÏVE", "straße", "STRASSE", "İstanbul", "ΣΊΣΥΦΟΣ", "σίσυφος",
            "漢字", "検索", "😀", "x²", "①", "Ⅻ", "user_id", "HTTP2", "v1.2.3", "foo-bar", "foo_bar", "ﬁle",
            "Ǆemo", "big0", "method", "a", "ab", "--", "(", ")", "::", "…"};
    private static final int FILES = 250;
    private static final int SYMBOLS_PER_FILE = 6;
    private static final Origin ORIGIN = new Origin("fixture", "TEST", "1", "run-a6", OriginType.OTHER);

    private HybridRankingCorpus() {
    }

    /** Écrit les sources sous {@code root}, enregistre le projet et publie son snapshot. */
    static RegisteredProject install(MinosApplication application, Path root) throws IOException {
        RegisteredProject project = application.projectRegistry().registerProject(root, PROJECT);
        publish(application, root, project, "hybrid-ranking-snapshot", "");
        return project;
    }

    /**
     * Réécrit les sources (identiques d'une fois sur l'autre) et publie un snapshot du corpus. Un {@code salt}
     * non vide change le nom de chaque symbole, donc le contenu de ses documents, sans changer leur nombre.
     */
    static void publish(MinosApplication application, Path root, RegisteredProject project, String snapshotId,
                        String salt) throws IOException {
        Random random = new Random(0xA6L);
        List<Symbol> symbols = new ArrayList<>();
        String projectId = project.id().toString();
        for (int file = 0; file < FILES; file++) {
            String fileId = String.format(Locale.ROOT, "src/pkg%02d/File%03d.java", file % 17, file);
            List<String> lines = new ArrayList<>();
            for (int line = 0; line < 40; line++) {
                StringBuilder text = new StringBuilder();
                for (int word = 0, count = 4 + random.nextInt(8); word < count; word++) {
                    if (word > 0) text.append(random.nextInt(5) == 0 ? ", " : " ");
                    text.append(WORDS[random.nextInt(WORDS.length)]);
                }
                lines.add(text.toString());
            }
            Path source = root.resolve(fileId);
            Files.createDirectories(source.getParent());
            Files.write(source, lines, StandardCharsets.UTF_8);
            for (int index = 0; index < SYMBOLS_PER_FILE; index++) {
                int line = 3 + index * 6;
                String name = WORDS[random.nextInt(WORDS.length)].replaceAll("[^\\p{L}\\p{N}_]", "") + index;
                if (name.length() == 1) name = "s" + name;
                name = name + salt;
                String id = "sym-" + file + "-" + index;
                symbols.add(new Symbol(id, "key:" + fileId + "#" + name + "@" + index, SymbolIdentityQuality.CANONICAL,
                        projectId, "module-" + (file % 5), fileId, null,
                        index == 0 ? SymbolKind.CLASS : SymbolKind.METHOD, name,
                        "pkg" + (file % 17) + "." + name, index == 0 ? null : "(" + lines.get(line).split(" ")[0] + ")",
                        "java", new SymbolLocation(fileId, line, 0, line + 2, 5, PositionEncoding.UTF16_CODE_UNITS),
                        ResolutionStatus.RESOLVED, ORIGIN, false, false, Set.of()));
            }
        }
        List<Relationship> relationships = new ArrayList<>();
        for (int index = 0; index < symbols.size(); index++) {
            // Degrés répétés : chaque symbole appelle 0 à 2 cibles choisies dans un petit ensemble de pivots.
            for (int edge = 0, count = random.nextInt(3); edge < count; edge++) {
                Symbol target = symbols.get(random.nextInt(24) * 7 % symbols.size());
                relationships.add(new Relationship("rel-" + index + "-" + edge, projectId,
                        new CodeEntityRef(CodeEntityType.SYMBOL, symbols.get(index).id()),
                        new CodeEntityRef(CodeEntityType.SYMBOL, target.id()), null, RelationshipKind.CALLS, null,
                        ResolutionStatus.RESOLVED, InformationNature.FACTUAL, null, ORIGIN, List.of()));
            }
        }
        application.snapshotStore().publish(project.id(), snapshotId, symbols, List.of(), relationships);
    }

    /** Classement complet de chaque requête, à l'octet près : une ligne par résultat. */
    static String rankings(MinosApplication application, String mode) throws IOException {
        StringBuilder out = new StringBuilder();
        ranking(application, mode, QUERIES, LIMIT, out);
        ranking(application, mode + "-" + FULL_LIMIT, FULL_QUERIES, FULL_LIMIT, out);
        return out.toString();
    }

    private static void ranking(MinosApplication application, String mode, List<String> queries, int limit,
                                StringBuilder out) throws IOException {
        for (String query : queries) {
            HybridSearchService.HybridResponse response = application.hybridSearchService()
                    .search(PROJECT, new HybridSearchService.HybridRequest(query, limit, 0.0));
            int rank = 0;
            for (HybridSearchService.HybridHit hit : response.hits()) {
                out.append(mode).append('\t').append(query).append('\t').append(++rank).append('\t')
                        .append(hit.document().stableKey()).append('\t')
                        .append(bits(hit.score())).append('\t').append(bits(hit.lexicalScore())).append('\t')
                        .append(bits(hit.graphScore())).append('\t').append(bits(hit.semanticScore())).append('\t')
                        .append(hit.rankingMode()).append('\n');
            }
        }
    }

    /** Nombre de paires de résultats consécutifs à score exactement égal, départagées par la clé stable. */
    static long exactTies(String rankings) {
        long ties = 0L;
        String[] previous = null;
        for (String line : rankings.split("\n")) {
            String[] fields = line.split("\t");
            if (previous != null && previous[1].equals(fields[1]) && previous[0].equals(fields[0])
                    && previous[4].equals(fields[4])) {
                ties++;
            }
            previous = fields;
        }
        return ties;
    }

    private static String bits(double value) {
        return Long.toHexString(Double.doubleToRawLongBits(value));
    }
}
