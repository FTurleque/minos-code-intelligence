package com.minos.characterization;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.minos.characterization.JsonSourceScanner.productionSources;
import static com.minos.characterization.JsonSourceScanner.relative;
import static com.minos.characterization.JsonSourceScanner.withoutComments;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Q7 — un seul point d'échappement JSON. Hors de {@code DeterministicJson}, aucune source de
 * production ne réécrit l'échappement à la main : commutateur sur le saut de page, séquence
 * {@code \\u} construite, appel {@code replace} dont le premier argument est la barre oblique
 * inverse.
 *
 * <p>Les exceptions sont nommées une à une, avec leur raison : ajouter une entrée à cette liste est
 * une décision de revue, pas un contournement.</p>
 */
class JsonEscapeGuardTest {

    /** Modules dont les sources de production sont scrutées. */
    private static final List<String> MODULES = List.of(
            "minos-domain", "minos-engine", "minos-runtime-local", "minos-storage-local", "minos-storage-postgresql",
            "minos-provider-scip", "minos-integration-git", "minos-application", "minos-bootstrap", "minos-nexus",
            "minos-cli", "minos-api", "minos-mcp", "minos-app");

    private static final String OUTPUT_DIRECTORY = "minos-application/src/main/java/com/minos/output";
    private static final String ENCODER = OUTPUT_DIRECTORY + "/DeterministicJson.java";

    /**
     * Signatures d'un échappement JSON écrit à la main. Elles sont volontairement étroites : elles
     * ne visent pas tout appel à {@code replace}.
     */
    private static final Map<String, Pattern> ESCAPE_SIGNATURES = Map.of(
            "commutateur sur le saut de page", Pattern.compile("case\\s+'\\\\f'"),
            "sequence unicode construite", Pattern.compile("\"\\\\\\\\u(%04x)?\""),
            "replace de la barre oblique inverse", Pattern.compile("replace\\(\\s*\"\\\\\\\\\"\\s*,"));

    /** Fichiers qui contiennent une signature sans écrire du JSON : {@code fichier -> raison}. */
    private static final Map<String, String> ESCAPE_EXCEPTIONS = Map.of(
            OUTPUT_DIRECTORY + "/ArchitectureResultRenderer.java",
            "échappement Graphviz DOT (dotText), pas du JSON ; le JSON de ce renderer passe par DeterministicJson",
            "minos-engine/src/main/java/com/minos/source/ProjectIgnoreRules.java",
            "échappement d'une classe de caractères d'expression régulière, pas du JSON");

    @Test
    void onlyDeterministicJsonEscapesJsonText() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : productionSources(MODULES)) {
            String relative = relative(file);
            if (relative.equals(ENCODER) || ESCAPE_EXCEPTIONS.containsKey(relative)) {
                continue;
            }
            String code = withoutComments(Files.readString(file, StandardCharsets.UTF_8));
            ESCAPE_SIGNATURES.forEach((name, signature) -> {
                if (signature.matcher(code).find()) offenders.add(relative + " : " + name);
            });
        }

        assertEquals(List.of(), offenders,
                "un seul point d'échappement JSON : DeterministicJson.quote (Q7). Fichiers qui échappent à la main");
    }

    @Test
    void everyListedExceptionStillExistsAndStillContainsTheSignature() throws IOException {
        for (String exception : ESCAPE_EXCEPTIONS.keySet()) {
            Path file = JsonSourceScanner.ROOT.resolve(exception);
            assertTrue(Files.isRegularFile(file), "exception périmée, le fichier n'existe plus : " + exception);
            String code = withoutComments(Files.readString(file, StandardCharsets.UTF_8));
            assertTrue(ESCAPE_SIGNATURES.values().stream().anyMatch(signature -> signature.matcher(code).find()),
                    "exception inutile, plus aucune signature dans " + exception);
        }
    }

    @Test
    void theEscapeSignaturesMatchWhatTheyAreMeantToCatch() {
        // « @ » tient lieu de barre oblique inverse, pour ne pas doubler chaque échappement Java.
        String handWritten = withoutComments("""
                String q(String v) { return v.replace("@@", "@@@@").replace("@"", "@@@""); }
                void s(char c) { switch (c) { case '@f' -> out("@@f"); default -> out("@@u%04x"); } }
                """.replace('@', '\\'));
        String innocent = withoutComments("""
                String q(String v) { return v.replace("a", "b").trim(); }
                void s(char c) { switch (c) { case 'x' -> out("y"); default -> out("z"); } }
                """);

        Set<String> matched = ESCAPE_SIGNATURES.entrySet().stream()
                .filter(entry -> entry.getValue().matcher(handWritten).find())
                .map(Map.Entry::getKey).collect(Collectors.toSet());
        assertEquals(ESCAPE_SIGNATURES.keySet(), matched);
        assertTrue(ESCAPE_SIGNATURES.values().stream().noneMatch(signature -> signature.matcher(innocent).find()));
    }
}
