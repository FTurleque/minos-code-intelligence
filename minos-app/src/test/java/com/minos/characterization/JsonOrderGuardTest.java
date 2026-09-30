package com.minos.characterization;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.minos.characterization.JsonSourceScanner.argumentCounts;
import static com.minos.characterization.JsonSourceScanner.codeOnly;
import static com.minos.characterization.JsonSourceScanner.productionSources;
import static com.minos.characterization.JsonSourceScanner.relative;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Q6 — aucune structure sans ordre défini ne peut alimenter une sortie JSON.
 *
 * <p>{@code Map.of}, {@code Map.ofEntries}, {@code Map.copyOf}, {@code Set.of}, {@code Set.copyOf}
 * itèrent dans un ordre tiré au hasard au démarrage de la JVM dès qu'ils portent deux éléments : un
 * renderer ou une commande qui les passe à {@code DeterministicJson} produit des clés dans un ordre
 * qui change d'un processus à l'autre. Les golden de caractérisation ne masquent plus l'ordre des clés
 * (voir {@link CharacterizationNormalizer}) ; ce test barre en amont, dès la source, les formes qui le
 * rendraient de nouveau aléatoire.</p>
 *
 * <p>Les exceptions sont nommées une à une, avec leur raison : ajouter une entrée à cette liste est
 * une décision de revue, pas un contournement.</p>
 */
class JsonOrderGuardTest {

    private static final String OUTPUT_DIRECTORY = "minos-application/src/main/java/com/minos/output";
    private static final String PROVIDER_VIEW =
            "minos-application/src/main/java/com/minos/application/ProviderPlatformService.java";
    private static final String CONFORMANCE_KIT =
            "minos-engine/src/main/java/com/minos/orchestration/ProviderConformanceKit.java";

    /**
     * Racines (dossiers ou fichiers) dont les <em>maps</em> atteignent une sortie JSON : les renderers,
     * la vue des providers et les trois surfaces.
     */
    private static final List<String> MAP_ROOTS = List.of(
            OUTPUT_DIRECTORY, PROVIDER_VIEW, CONFORMANCE_KIT,
            "minos-api/src/main/java", "minos-mcp/src/main/java", "minos-cli/src/main/java");

    /**
     * Racines dont les <em>ensembles</em> atteignent une sortie JSON. Les surfaces n'y figurent pas : leurs
     * {@code Set.of(options)} servent à tester l'appartenance d'une option, jamais à itérer.
     */
    private static final List<String> SET_ROOTS = List.of(OUTPUT_DIRECTORY, PROVIDER_VIEW, CONFORMANCE_KIT);

    /** Usages de {@code Map.copyOf} qui ne produisent aucune sortie : {@code fichier -> raison}. */
    private static final Map<String, String> MAP_COPY_EXCEPTIONS = new LinkedHashMap<>();

    static {
        MAP_COPY_EXCEPTIONS.put("minos-cli/src/main/java/com/minos/cli/MinosCli.java",
                "table de sous-commandes consultée par clé, jamais rendue");
        MAP_COPY_EXCEPTIONS.put("minos-cli/src/main/java/com/minos/cli/IdeIntelligenceCommand.java",
                "Options.values : table de recherche des options de la ligne de commande, jamais rendue");
    }

    @Test
    void noUnorderedMapOrSetCanFeedAJsonOutput() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : productionSources(MAP_ROOTS)) {
            String relative = relative(file);
            String code = codeOnly(Files.readString(file, StandardCharsets.UTF_8));
            for (int arguments : argumentCounts(code, "Map.of")) {
                if (arguments > 2) offenders.add(relative + " : Map.of a " + arguments / 2 + " entrees");
            }
            if (!argumentCounts(code, "Map.ofEntries").isEmpty()) offenders.add(relative + " : Map.ofEntries");
            if (!argumentCounts(code, "Map.copyOf").isEmpty() && !MAP_COPY_EXCEPTIONS.containsKey(relative)) {
                offenders.add(relative + " : Map.copyOf");
            }
        }
        for (Path file : productionSources(SET_ROOTS)) {
            String relative = relative(file);
            String code = codeOnly(Files.readString(file, StandardCharsets.UTF_8));
            for (int arguments : argumentCounts(code, "Set.of")) {
                if (arguments > 1) offenders.add(relative + " : Set.of a " + arguments + " elements");
            }
            if (!argumentCounts(code, "Set.copyOf").isEmpty()) offenders.add(relative + " : Set.copyOf");
        }
        for (Path file : productionSources(List.of(OUTPUT_DIRECTORY))) {
            String code = codeOnly(Files.readString(file, StandardCharsets.UTF_8));
            for (String token : List.of("Collectors.toSet(", "Collectors.toUnmodifiableSet(",
                    "Collectors.toUnmodifiableMap(", "new HashMap", "new HashSet")) {
                if (code.contains(token)) offenders.add(relative(file) + " : " + token);
            }
        }

        assertEquals(List.of(), offenders,
                "structure sans ordre défini sur un chemin de sortie JSON : LinkedHashMap, TreeMap, "
                        + "DeterministicJson.object, ProviderConformanceKit.sortedCopy (Q6)");
    }

    @Test
    void everyListedExceptionStillExistsAndStillUsesMapCopyOf() throws IOException {
        for (String exception : MAP_COPY_EXCEPTIONS.keySet()) {
            Path file = JsonSourceScanner.ROOT.resolve(exception);
            assertTrue(Files.isRegularFile(file), "exception périmée, le fichier n'existe plus : " + exception);
            String code = codeOnly(Files.readString(file, StandardCharsets.UTF_8));
            assertTrue(!argumentCounts(code, "Map.copyOf").isEmpty(), "exception inutile dans " + exception);
        }
    }

    @Test
    void theGuardRecognisesTheUnorderedFormsItIsMeantToCatch() {
        String code = codeOnly("""
                class Sample {
                    // Map.of("commentaire", 1, "ignoré", 2)
                    Object a = Map.of("k", "un,deux(trois)", "l", List.of(1, 2));
                    Object b = Map.of("seul", 1);
                    Object c = Map.of();
                    Object d = Set.of("x", "y");
                    Object e = Set.of("x");
                    Object f = java.util.Map.of("p", 1, "q", 2);
                    Object g = EnumSet.of(A, B);
                }
                """);

        assertEquals(List.of(4, 2, 0, 4), argumentCounts(code, "Map.of"));
        assertEquals(List.of(2, 1), argumentCounts(code, "Set.of"));
    }
}
