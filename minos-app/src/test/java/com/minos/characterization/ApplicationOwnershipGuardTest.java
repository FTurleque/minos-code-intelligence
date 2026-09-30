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
import static com.minos.characterization.JsonSourceScanner.codeOnly;
import static com.minos.characterization.JsonSourceScanner.productionSources;
import static com.minos.characterization.JsonSourceScanner.relative;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Q10 : toute application ouverte en production a un propriétaire qui la ferme. Une garde de source, dans l'esprit
 * de {@link JsonOrderGuardTest} : elle lit les sources de production de tous les modules et refuse toute création
 * d'une {@code MinosApplication} ({@code MinosApplication.open(...)}, {@code MinosApplication.builder(...)},
 * ou la référence de méthode {@code MinosApplication::open}) qui n'est ni
 * <ul>
 *   <li>l'en-tête d'un {@code try}-avec-ressources, qui la ferme sur tous les chemins ;</li>
 *   <li>dans un fichier de la liste des propriétaires ci-dessous, dont la raison est écrite, et dont la preuve de
 *       fermeture (un fragment de source) doit exister : un propriétaire qui cesse de fermer fait échouer la garde.</li>
 * </ul>
 * Une nouvelle surface qui ouvrirait l'application sans la fermer fait donc rougir la suite. Ajouter une entrée à
 * la liste est une décision de revue, pas un contournement.
 */
class ApplicationOwnershipGuardTest {

    private static final Pattern CREATION = Pattern.compile(
            "MinosApplication\\s*(?:\\.\\s*(?:open|builder)\\s*\\(|::\\s*open\\b)");

    /** L'instruction qui précède la création est l'en-tête d'un try-avec-ressources sur une MinosApplication. */
    private static final Pattern RESOURCE_HEADER = Pattern.compile(
            "\\btry\\s*\\(\\s*(?:final\\s+)?MinosApplication\\s+\\w+\\s*=\\s*$");

    /** Fichier de production -> pourquoi il peut créer une application sans try-avec-ressources. */
    private static final Map<String, String> OWNERS = new LinkedHashMap<>();

    /**
     * Fichier de production -> fragments de source qui prouvent que l'application est fermée. Pour le routeur MCP et
     * le lanceur, le second fragment verrouille le câblage de production (l'ouverture réelle est injectée dans la
     * méthode qui ferme) : aucun test ne peut ouvrir une vraie application avec un magasin espion.
     */
    private static final Map<String, List<String>> CLOSING_EVIDENCE = new LinkedHashMap<>();

    static {
        OWNERS.put("minos-application/src/main/java/com/minos/application/LocalProjectOperations.java",
                "constructeur (Path) : possède l'application, close() la ferme");
        OWNERS.put("minos-cli/src/main/java/com/minos/cli/LocalAutonomousIndexOperations.java",
                "constructeur (Path) : possède l'application, close() la ferme");
        OWNERS.put("minos-mcp/src/main/java/com/minos/mcp/MinosMcpTools.java",
                "constructeur (Path) : possède l'application, close() la ferme");
        OWNERS.put("minos-api/src/main/java/com/minos/api/MinosApiSupport.java",
                "openApplication : l'application ouverte est possédée par la façade qui l'appelle (trois fichiers ci-dessous)");
        OWNERS.put("minos-app/src/main/java/com/minos/app/McpBackendRouter.java",
                "référence MinosApplication::open injectée dans serving(), qui ferme dans un try-avec-ressources");
        OWNERS.put("minos-cli/src/main/java/com/minos/cli/MinosLauncher.java",
                "référence MinosApplication::open injectée dans launch(), qui ferme dans un try-avec-ressources");

        CLOSING_EVIDENCE.put("minos-application/src/main/java/com/minos/application/LocalProjectOperations.java",
                List.of("ownedApplication.close()"));
        CLOSING_EVIDENCE.put("minos-cli/src/main/java/com/minos/cli/LocalAutonomousIndexOperations.java",
                List.of("ownedApplication.close()"));
        CLOSING_EVIDENCE.put("minos-mcp/src/main/java/com/minos/mcp/MinosMcpTools.java",
                List.of("ownedApplication.close()"));
        CLOSING_EVIDENCE.put("minos-api/src/main/java/com/minos/api/LocalMinosApi.java",
                List.of("if (!ownsApplication) return;"));
        CLOSING_EVIDENCE.put("minos-api/src/main/java/com/minos/api/LocalMinosMultiRepositoryApi.java",
                List.of("if (!ownsApplication) return;"));
        CLOSING_EVIDENCE.put("minos-api/src/main/java/com/minos/api/LocalProviderPlatformApi.java",
                List.of("if (!ownsApplication) return;"));
        CLOSING_EVIDENCE.put("minos-app/src/main/java/com/minos/app/McpBackendRouter.java",
                List.of("try (MinosApplication application = opener.open(home))",
                        "this(serving(MinosApplication::open, MinosMcpServer::run),"));
        CLOSING_EVIDENCE.put("minos-cli/src/main/java/com/minos/cli/MinosLauncher.java",
                List.of("try (MinosApplication application = opener.open(home))",
                        "MinosApplication::open, MinosLauncher::run));"));
    }

    @Test
    void everyApplicationCreatedInProductionHasAnOwnerThatClosesIt() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : productionSources(modules())) {
            String relative = relative(file);
            if (OWNERS.containsKey(relative)) continue;
            String code = codeOnly(Files.readString(file, StandardCharsets.UTF_8));
            Matcher creation = CREATION.matcher(code);
            while (creation.find()) {
                if (!RESOURCE_HEADER.matcher(statementBefore(code, creation.start())).find()) {
                    offenders.add(relative + " : " + creation.group().strip() + " hors d'un try-avec-ressources");
                }
            }
        }
        assertEquals(List.of(), offenders,
                "une application est ouverte sans propriétaire qui la ferme : try-avec-ressources, ou propriétaire déclaré");
    }

    @Test
    void everyDeclaredOwnerStillCreatesAnApplicationAndStillClosesIt() throws IOException {
        List<String> stale = new ArrayList<>();
        for (String owner : OWNERS.keySet()) {
            String code = codeOnly(Files.readString(ROOT.resolve(owner), StandardCharsets.UTF_8));
            if (!CREATION.matcher(code).find() && !code.contains("openApplication(")) {
                stale.add(owner + " : ne crée plus d'application, retirer l'entrée");
            }
        }
        for (Map.Entry<String, List<String>> evidence : CLOSING_EVIDENCE.entrySet()) {
            String source = Files.readString(ROOT.resolve(evidence.getKey()), StandardCharsets.UTF_8)
                    .replaceAll("\\s+", " ");
            for (String fragment : evidence.getValue()) {
                if (!source.contains(fragment)) {
                    stale.add(evidence.getKey() + " : la preuve de fermeture « " + fragment + " » a disparu");
                }
            }
        }
        assertEquals(List.of(), stale);
    }

    /** Le texte de l'instruction en cours, du dernier « ; », « { » ou « } » jusqu'à {@code position}. */
    private static String statementBefore(String code, int position) {
        int start = Math.max(code.lastIndexOf(';', position),
                Math.max(code.lastIndexOf('{', position), code.lastIndexOf('}', position))) + 1;
        return code.substring(start, position);
    }

    /** Les modules Maven qui portent des sources de production. */
    private static List<String> modules() throws IOException {
        try (Stream<Path> children = Files.list(ROOT)) {
            return children
                    .filter(path -> path.getFileName().toString().startsWith("minos-"))
                    .filter(path -> Files.isDirectory(path.resolve("src/main/java")))
                    .map(path -> path.getFileName().toString() + "/src/main/java")
                    .sorted()
                    .toList();
        }
    }
}
