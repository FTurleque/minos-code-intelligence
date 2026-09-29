package com.minos.bootstrap.semantic;

import com.minos.application.MinosApplication;
import com.minos.application.semantic.HybridSearchService;
import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.PositionEncoding;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.domain.SymbolLocation;
import com.minos.registry.RegisteredProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Le poids estimé d'un corpus hybride majore le tas qu'il retient réellement, quel que soit l'alphabet (lot A6,
 * V-A6-11), et un corpus ASCII ne pèse pas plus qu'au code de {@code d9ae1005}, ce qui garde la même limite
 * d'admission dans le cache.
 *
 * <p>Tas retenu : tas utilisé après ramasse-miettes, avant et après la première recherche, une fois le snapshot
 * et l'état sémantique déjà chargés, comme la sonde de verif-archi. Poids de référence de la base : obtenus en
 * lançant ce test avec {@code -Dminos.corpusWeight.print=true} sur le {@code HybridSearchService} de
 * {@code d9ae1005} (procédure dans {@code ARCHI-SUIVI.md}, § A6.15).</p>
 */
class HybridCorpusWeightTest {

    private static final String PRINT_PROPERTY = "minos.corpusWeight.print";
    private static final Origin ORIGIN = new Origin("fixture", "TEST", "1", "run-weight", OriginType.OTHER);
    private static final String[] ASCII = {"snapshot", "store", "query", "normalize", "hybrid", "search", "pointer"};
    private static final String[] LATIN1 = {"été", "naïve", "façade", "cœur", "straße", "àéîõü", "déjà"};
    private static final String[] CJK = {"漢字", "検索", "索引", "快照", "查询", "规范化", "指针"};
    /** Poids du même corpus ASCII au code de d9ae1005 (propriété {@value #PRINT_PROPERTY}). */
    private static final Map<String, Long> BASE_ASCII_WEIGHTS = Map.of(
            "ascii-short", 3_268_704L,
            "ascii-long", 9_055_400L);

    @Test
    void estimateBoundsTheRetainedHeapForEveryAlphabetAndLineLength(@TempDir Path temp) throws Exception {
        List<String> failures = new ArrayList<>();
        measure(temp, "ascii-short", List.<String[]>of(ASCII), 6, failures);
        measure(temp, "ascii-long", List.<String[]>of(ASCII), 120, failures);
        measure(temp, "latin1-short", List.<String[]>of(LATIN1), 6, failures);
        measure(temp, "latin1-long", List.<String[]>of(LATIN1), 120, failures);
        measure(temp, "cjk-short", List.<String[]>of(CJK), 6, failures);
        measure(temp, "cjk-long", List.<String[]>of(CJK), 120, failures);
        measure(temp, "mixed-long", List.<String[]>of(ASCII, LATIN1, CJK), 120, failures);
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    private static void measure(Path temp, String label, List<String[]> vocabularies, int wordsPerLine,
                                List<String> failures) throws Exception {
        MinosApplication application = MinosApplication.builder(temp.resolve(label).resolve("home")).build();
        RegisteredProject project = install(application, Files.createDirectories(temp.resolve(label).resolve("project")),
                vocabularies, wordsPerLine);
        application.snapshotStore().loadActiveKnowledge(project.id());
        application.semanticIndexService().status(project.id().toString());
        long before = settledHeap();
        application.hybridSearchService().search(project.id().toString(),
                new HybridSearchService.HybridRequest("snapshot 漢字 été", 5, 0.0));
        long retained = settledHeap() - before;
        long estimate = application.hybridSearchService().corpusCacheStats().weightBytes();
        String line = String.format(Locale.ROOT, "%s estimate=%d retained=%d ratio=%.2f",
                label, estimate, retained, estimate / (double) retained);
        if (Boolean.getBoolean(PRINT_PROPERTY)) {
            System.out.println("CORPUS-WEIGHT " + line);
            return;
        }
        if (estimate < retained) failures.add("estimate below retained heap: " + line);
        Long base = BASE_ASCII_WEIGHTS.get(label);
        if (base != null && estimate > base) failures.add("ASCII corpus heavier than at d9ae1005 (" + base + "): " + line);
    }

    private static RegisteredProject install(MinosApplication application, Path root, List<String[]> vocabularies,
                                             int wordsPerLine) throws Exception {
        Random random = new Random(0xA611L);
        RegisteredProject project = application.projectRegistry().registerProject(root, "corpus-weight");
        List<Symbol> symbols = new ArrayList<>();
        for (int file = 0; file < 120; file++) {
            String fileId = String.format(Locale.ROOT, "src/File%03d.txt", file);
            List<String> lines = new ArrayList<>();
            for (int line = 0; line < 60; line++) {
                StringBuilder text = new StringBuilder();
                for (int word = 0; word < wordsPerLine; word++) {
                    String[] vocabulary = vocabularies.get(random.nextInt(vocabularies.size()));
                    text.append(word == 0 ? "" : " ").append(vocabulary[random.nextInt(vocabulary.length)]);
                }
                lines.add(text.toString());
            }
            Files.createDirectories(root.resolve(fileId).getParent());
            Files.write(root.resolve(fileId), lines, StandardCharsets.UTF_8);
            for (int index = 0; index < 10; index++) {
                int line = 2 + index * 5;
                String[] vocabulary = vocabularies.get(random.nextInt(vocabularies.size()));
                String name = vocabulary[random.nextInt(vocabulary.length)] + index;
                symbols.add(new Symbol("sym-" + file + "-" + index, "key:" + fileId + "#" + index,
                        SymbolIdentityQuality.CANONICAL, project.id().toString(), "module", fileId, null,
                        SymbolKind.METHOD, name, "pkg." + name, "(" + name + ")", "java",
                        new SymbolLocation(fileId, line, 0, line + 2, 1, PositionEncoding.UTF16_CODE_UNITS),
                        ResolutionStatus.RESOLVED, ORIGIN, false, false, Set.of()));
            }
        }
        application.snapshotStore().publish(project.id(), "corpus-weight-snapshot", symbols, List.of(), List.of());
        return project;
    }

    private static long settledHeap() throws InterruptedException {
        for (int round = 0; round < 4; round++) {
            System.gc();
            Thread.sleep(150L);
        }
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }
}
