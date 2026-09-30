package com.minos.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Q11 : le parseur d'arguments reste UN. Deux gardes de source, dans l'esprit de {@code JsonEscapeGuardTest} :
 * <ul>
 *   <li>plus aucune commande n'écrit sa propre analyse (test de préfixe « -- », conversion numérique,
 *       conversion de casse sans {@code Locale}, test d'aide à la main) : ces formes n'existent que dans
 *       {@link CliOptions} et {@link CliCommandSupport} (exceptions nommées et justifiées) ;</li>
 *   <li>toute commande de premier niveau de la table de routes de {@link MinosCli} figure dans un test de
 *       règles uniformes : une commande ajoutée sans ligne dans ces tests fait échouer la garde.</li>
 * </ul>
 */
class CliParsingGuardTest {

    private static final Path MAIN = moduleDirectory("src/main/java/com/minos/cli");
    private static final Path TEST = moduleDirectory("src/test/java/com/minos/cli");

    /** Le répertoire, que les tests tournent depuis le module ou depuis la racine du réacteur. */
    private static Path moduleDirectory(String relative) {
        for (Path candidate : new Path[]{Path.of(relative), Path.of("minos-cli").resolve(relative)}) {
            if (Files.isDirectory(candidate)) return candidate;
        }
        throw new IllegalStateException(relative + " not found from " + Path.of("").toAbsolutePath());
    }

    /** Fichier -> raison, pour les rares occurrences légitimes de chaque forme. */
    private static final Map<String, String> ALLOWED_DASH_PREFIX = new LinkedHashMap<>();

    static {
        ALLOWED_DASH_PREFIX.put("CliOptions.java", "l'analyseur commun lui-même");
        ALLOWED_DASH_PREFIX.put("CliCommandSupport.java", "operand() : règle unique des opérandes (pas de tiret en tête)");
        ALLOWED_DASH_PREFIX.put("IdeIntelligenceCommand.java",
                "requirePositions() : les opérandes de ide (projet, symbole, requête) portent du texte libre, seul « -- » est refusé");
    }

    private static List<Path> mainSources() throws IOException {
        try (Stream<Path> files = Files.list(MAIN)) {
            return files.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        }
    }

    /** Le code d'un source : commentaires retirés, littéraux conservés (les motifs ci-dessous regardent les littéraux). */
    private static String code(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        return text.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }

    @Test
    void onlyTheSharedParserLooksAtOptionPrefixes() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : mainSources()) {
            String name = file.getFileName().toString();
            if (ALLOWED_DASH_PREFIX.containsKey(name)) continue;
            String code = code(file);
            if (code.contains("startsWith(\"-")) offenders.add(name + " : test de préfixe de tiret");
        }
        assertEquals(List.of(), offenders,
                "une commande analyse ses propres options : déclarer l'option dans CliOptions.spec()");
    }

    @Test
    void numbersAreParsedAndBoundedOnlyByTheSharedParser() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : mainSources()) {
            String name = file.getFileName().toString();
            if (name.equals("CliOptions.java")) continue;
            String code = code(file);
            if (code.contains("Integer.parseInt(") || code.contains("Double.parseDouble(")) {
                offenders.add(name + " : conversion numérique d'un argument");
            }
        }
        assertEquals(List.of(), offenders,
                "les bornes sont déclarées avec l'option (CliOptions.Spec.integer) et contrôlées à l'analyse (code 2)");
    }

    @Test
    void everyCaseConversionNamesItsLocale() throws IOException {
        List<String> offenders = new ArrayList<>();
        Pattern withoutLocale = Pattern.compile("\\.to(Lower|Upper)Case\\(\\s*\\)");
        for (Path file : mainSources()) {
            if (withoutLocale.matcher(code(file)).find()) offenders.add(file.getFileName().toString());
        }
        assertEquals(List.of(), offenders, "toLowerCase()/toUpperCase() sans Locale.ROOT");
    }

    @Test
    void helpIsRecognisedInOnePlace() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : mainSources()) {
            String name = file.getFileName().toString();
            if (name.equals("CliCommandSupport.java")) continue;
            String code = code(file);
            if (code.contains("\"--help\".equals(") || code.contains("\"-h\".equals(")) offenders.add(name);
        }
        assertEquals(List.of(), offenders, "utiliser CliCommandSupport.isHelp");
    }

    @Test
    void theGuardRecognisesTheHandWrittenParsingItIsMeantToCatch() {
        String handWritten = "if (value.startsWith(\"--\")) { limit = Integer.parseInt(value); name = x.toLowerCase(); }";
        assertTrue(handWritten.contains("startsWith(\"-"));
        assertTrue(handWritten.contains("Integer.parseInt("));
        assertTrue(Pattern.compile("\\.to(Lower|Upper)Case\\(\\s*\\)").matcher(handWritten).find());
    }

    /** Noms que nomme un test de règles dans {@code command("label", "<nom> ...")} ou dans une boucle sur {@code Operation}. */
    private static Set<String> commandsUnderRulesTests() throws IOException {
        Set<String> covered = new java.util.TreeSet<>();
        Pattern base = Pattern.compile("command\\(\\s*\"[^\"]*\"\\s*,\\s*\"([a-z][a-z-]*)[ \"]");
        try (Stream<Path> files = Files.list(TEST)) {
            for (Path file : files.filter(path -> path.toString().endsWith("ArgumentRulesTest.java")
                    || path.toString().endsWith("IdeIntelligenceCommandTest.java")).toList()) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                var matcher = base.matcher(text);
                while (matcher.find()) covered.add(matcher.group(1));
                if (text.contains("RelationshipCommand.Operation.values()")) {
                    for (RelationshipCommand.Operation operation : RelationshipCommand.Operation.values()) {
                        covered.add(operation.commandName());
                    }
                }
            }
        }
        return covered;
    }

    @Test
    void everyTopLevelCommandIsUnderTheUniformArgumentRulesTests() throws IOException {
        Set<String> covered = commandsUnderRulesTests();
        List<String> missing = MinosCliRunner.statelessHelpCommands().stream()
                .filter(name -> !covered.contains(name))
                .collect(Collectors.toList());
        assertEquals(List.of(), missing,
                "commande(s) de la table de routes sans ligne dans un *ArgumentRulesTest : ajouter "
                        + "command(\"<nom>\", \"<nom> …\") dans le test de règles correspondant");
    }
}
