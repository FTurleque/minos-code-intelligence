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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.minos.characterization.JsonSourceScanner.ROOT;
import static com.minos.characterization.JsonSourceScanner.productionSources;
import static com.minos.characterization.JsonSourceScanner.relative;
import static com.minos.characterization.JsonSourceScanner.withoutComments;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Q13 — garde de source : une copie d'un helper mutualisé ne réapparaît pas. Pour chaque helper, le seul
 * emplacement autorisé est nommé ; ce qui reste dupliqué sciemment est déclaré une fois, avec sa raison
 * (règles de dépendance de {@code check-module-boundaries.py}, API publique). Ajouter une entrée à une liste
 * d'exceptions est une décision de revue, pas un contournement.
 */
class DuplicationGuardTest {

    private static final List<String> MODULES = List.of(
            "minos-domain", "minos-engine", "minos-runtime-local", "minos-storage-local", "minos-storage-postgresql",
            "minos-provider-scip", "minos-integration-git", "minos-application", "minos-bootstrap", "minos-nexus",
            "minos-cli", "minos-api", "minos-mcp", "minos-app");

    private static final String PRECONDITIONS = "minos-domain/src/main/java/com/minos/domain/Preconditions.java";
    private static final String SHA256 = "minos-engine/src/main/java/com/minos/io/Sha256.java";

    // --- requireText -------------------------------------------------------------------------------

    private static final Pattern REQUIRE_TEXT_DEFINITION = Pattern.compile(
            "\\b(?:void|String)\\s+requireText\\s*\\(");

    @Test
    void requireTextIsDefinedOnlyInPreconditions() throws IOException {
        List<String> definitions = new ArrayList<>();
        for (Path file : productionSources(MODULES)) {
            String code = withoutComments(Files.readString(file, StandardCharsets.UTF_8));
            Matcher matcher = REQUIRE_TEXT_DEFINITION.matcher(code);
            while (matcher.find()) definitions.add(relative(file));
        }

        assertEquals(List.of(PRECONDITIONS), definitions,
                "requireText vit une seule fois, dans com.minos.domain.Preconditions (Q13) ; copies trouvees");
    }

    // --- SHA-256 -------------------------------------------------------------------------------------

    private static final Pattern DIGEST_FACTORY = Pattern.compile("MessageDigest\\s*\\.\\s*getInstance\\s*\\(");
    private static final Pattern NO_SUCH_ALGORITHM = Pattern.compile("NoSuchAlgorithmException");
    private static final Pattern TEXT_HASH_METHOD = Pattern.compile(
            "\\bString\\s+sha256\\s*\\(\\s*(?:String|MessageDigest)\\s+\\w+\\s*\\)");

    @Test
    void theShaDigestIsCreatedOnlyBySha256() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : productionSources(MODULES)) {
            String relative = relative(file);
            if (relative.equals(SHA256)) continue;
            String code = withoutComments(Files.readString(file, StandardCharsets.UTF_8));
            if (DIGEST_FACTORY.matcher(code).find()) offenders.add(relative + " : MessageDigest.getInstance");
            if (NO_SUCH_ALGORITHM.matcher(code).find()) offenders.add(relative + " : NoSuchAlgorithmException");
            if (TEXT_HASH_METHOD.matcher(code).find()) offenders.add(relative + " : methode sha256(texte)");
        }

        assertEquals(List.of(), offenders,
                "SHA-256 vit dans com.minos.io.Sha256 (Q13) : fabrique, texte -> hexadecimal, capture de "
                        + "NoSuchAlgorithmException. Copies trouvees");
    }

    // --- JSON ecrit a la main -------------------------------------------------------------------------

    /**
     * Ouverture d'un objet JSON a la main : un {@code append} du caractere ouvrant, un tampon initialise avec lui,
     * ou un litteral de chaine qui commence par l'accolade ouvrante suivie d'un guillemet ou d'un saut de ligne
     * echappes.
     */
    private static final Pattern HAND_WRITTEN_JSON = Pattern.compile(
            "append\\s*\\(\\s*'\\{'\\s*\\)|append\\s*\\(\\s*\"\\{|new\\s+StringBuilder\\s*\\(\\s*\"\\{"
                    + "|\"\\{\\\\\"|\"\\{\\\\n");

    /** Fichiers qui contiennent la signature sans etre une reecriture de {@code DeterministicJson} : raison. */
    private static final Map<String, String> JSON_EXCEPTIONS = new LinkedHashMap<>();

    static {
        JSON_EXCEPTIONS.put("minos-application/src/main/java/com/minos/output/DeterministicJson.java",
                "l'encodeur lui-meme");
        JSON_EXCEPTIONS.put("minos-mcp/src/main/java/com/minos/mcp/McpToolSchemas.java",
                "gabarits de schemas d'outils : constantes, aucune donnee externe (declare depuis le lot 1)");
        JSON_EXCEPTIONS.put("minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/LockedNpmPackage.java",
                "ecrit un package.json npm de constantes du catalogue ; minos-provider-scip est un adaptateur, il ne "
                        + "peut pas dependre de minos-application ou vit DeterministicJson (A2, ADR 0042)");
    }

    @Test
    void jsonIsWrittenByDeterministicJsonNotByHand() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : productionSources(MODULES)) {
            String relative = relative(file);
            if (JSON_EXCEPTIONS.containsKey(relative)) continue;
            String code = withoutComments(Files.readString(file, StandardCharsets.UTF_8));
            if (HAND_WRITTEN_JSON.matcher(code).find()) offenders.add(relative);
        }

        assertEquals(List.of(), offenders,
                "le JSON passe par DeterministicJson (Q13) : sources qui ouvrent un objet a la main");
    }

    @Test
    void everyJsonExceptionStillExistsAndStillContainsTheSignature() throws IOException {
        for (String exception : JSON_EXCEPTIONS.keySet()) {
            Path file = ROOT.resolve(exception);
            assertTrue(Files.isRegularFile(file), "exception perimee, le fichier n'existe plus : " + exception);
            if (exception.endsWith("DeterministicJson.java")) continue;
            String code = withoutComments(Files.readString(file, StandardCharsets.UTF_8));
            assertTrue(HAND_WRITTEN_JSON.matcher(code).find(), "exception inutile, plus de signature dans " + exception);
        }
    }

    // --- LogCapture -------------------------------------------------------------------------------------

    @Test
    void logCaptureExistsOnceInTheTestSources() throws IOException {
        List<String> copies = new ArrayList<>();
        for (String module : MODULES) {
            Path tests = ROOT.resolve(module).resolve("src/test/java");
            if (!Files.isDirectory(tests)) continue;
            try (Stream<Path> walk = Files.walk(tests)) {
                walk.filter(path -> path.getFileName().toString().equals("LogCapture.java"))
                        .forEach(path -> copies.add(relative(path)));
            }
        }

        assertEquals(List.of("minos-engine/src/test/java/com/minos/testsupport/LogCapture.java"), copies,
                "LogCapture vit dans le test-jar de minos-engine (Q13)");
    }

    // --- mapping DTO ----------------------------------------------------------------------------------------

    @Test
    void theImportResultMapIsBuiltOnceInTheCli() throws IOException {
        int builders = 0;
        for (Path file : productionSources(List.of("minos-cli"))) {
            String code = withoutComments(Files.readString(file, StandardCharsets.UTF_8));
            Matcher matcher = Pattern.compile("put\\(\\s*\"normalizedSymbolCount\"").matcher(code);
            while (matcher.find()) builders++;
        }

        assertEquals(1, builders, "le resultat d'un import est mis en map a un seul endroit de la CLI");
    }

    @Test
    void theProjectMapsOfTheCliAndTheMcpShareOneProjection() throws IOException {
        Pattern projectKeys = Pattern.compile("\"rootAvailable\"\\s*,");
        List<String> builders = new ArrayList<>();
        for (Path file : productionSources(MODULES)) {
            String code = withoutComments(Files.readString(file, StandardCharsets.UTF_8));
            if (projectKeys.matcher(code).find()) builders.add(relative(file));
        }

        assertEquals(List.of("minos-application/src/main/java/com/minos/output/ProjectJson.java"), builders,
                "la projection JSON d'un projet vit dans com.minos.output.ProjectJson");
    }

    // --- ProjectView : duplication acceptee, plafonnee ------------------------------------------------------

    @Test
    void projectViewStaysAtTwoRecordsUntilThePublicSignaturesMayChange() throws IOException {
        List<String> records = new ArrayList<>();
        Pattern declaration = Pattern.compile("\\brecord\\s+ProjectView\\s*\\(");
        for (Path file : productionSources(MODULES)) {
            String code = withoutComments(Files.readString(file, StandardCharsets.UTF_8));
            if (declaration.matcher(code).find()) records.add(relative(file));
        }

        assertEquals(List.of(
                        "minos-application/src/main/java/com/minos/application/ProjectInspectionService.java",
                        "minos-application/src/main/java/com/minos/application/ProjectOperations.java"),
                records,
                "deux ProjectView, conserves : chacun est un type public observe par un autre module "
                        + "(CODE-SUIVI 21.3) ; une troisieme copie est refusee");
    }
}
