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
 *   <li>l'une des créations déclarées d'un fichier propriétaire ci-dessous : leur NOMBRE est figé (une création de
 *       plus dans un propriétaire est un site nouveau, donc refusée), et les fragments de code qui la ferment ou la
 *       câblent doivent exister dans le code, commentaires retirés (fermeture mise en commentaire = garde rouge).</li>
 * </ul>
 * Une nouvelle surface qui ouvrirait l'application sans la fermer fait donc rougir la suite. Ajouter ou modifier une
 * entrée est une décision de revue, pas un contournement.
 */
class ApplicationOwnershipGuardTest {

    private static final Pattern CREATION = Pattern.compile(
            "MinosApplication\\s*(?:\\.\\s*(?:open|builder)\\s*\\(|::\\s*open\\b)");

    /** L'instruction qui précède la création est l'en-tête d'un try-avec-ressources sur une MinosApplication. */
    private static final Pattern RESOURCE_HEADER = Pattern.compile(
            "\\btry\\s*\\(\\s*(?:final\\s+)?MinosApplication\\s+\\w+\\s*=\\s*$");

    /**
     * Un fichier de production qui crée une application sans try-avec-ressources : combien de créations il déclare,
     * pourquoi, et les fragments de code qui prouvent qu'elle est fermée (ou, pour le routeur MCP et le lanceur,
     * que l'ouverture réelle est injectée dans la méthode qui ferme : une vraie application ne s'ouvre pas avec un
     * magasin espion, le câblage de production n'est verrouillé que par ce fragment).
     */
    private record Owner(int creations, String reason, List<String> proof) { }

    private static final Map<String, Owner> OWNERS = new LinkedHashMap<>();

    /** Fichiers qui reçoivent l'application ouverte par {@code MinosApiSupport} : ils la ferment, sans la créer. */
    private static final Map<String, List<String>> CLOSERS = new LinkedHashMap<>();

    static {
        OWNERS.put("minos-application/src/main/java/com/minos/application/LocalProjectOperations.java",
                new Owner(1, "constructeur (Path) : possède l'application, close() la ferme",
                        List.of("this(MinosApplication.open(home), true)", "ownedApplication.close()")));
        OWNERS.put("minos-cli/src/main/java/com/minos/cli/LocalAutonomousIndexOperations.java",
                new Owner(1, "constructeur (Path) : possède l'application, close() la ferme",
                        List.of("this(MinosApplication.open(minosHome), UnaryOperator.identity(), true)",
                                "ownedApplication.close()")));
        OWNERS.put("minos-mcp/src/main/java/com/minos/mcp/MinosMcpTools.java",
                new Owner(1, "constructeur (Path) : possède l'application, close() la ferme",
                        List.of("this.ownedApplication = MinosApplication.open(normalizedHome)",
                                "ownedApplication.close()")));
        OWNERS.put("minos-api/src/main/java/com/minos/api/MinosApiSupport.java",
                new Owner(1, "openApplication : l'application ouverte est possédée par la façade qui l'appelle (CLOSERS)",
                        List.of("static MinosApplication openApplication(")));
        OWNERS.put("minos-app/src/main/java/com/minos/app/McpBackendRouter.java",
                new Owner(1, "référence MinosApplication::open injectée dans serving(), qui ferme dans un try-avec-ressources",
                        List.of("try (MinosApplication application = opener.open(home))",
                                "this(serving(MinosApplication::open, MinosMcpServer::run),")));
        OWNERS.put("minos-cli/src/main/java/com/minos/cli/MinosLauncher.java",
                new Owner(1, "référence MinosApplication::open injectée dans launch(), qui la confie à une LazyApplication "
                        + "ouverte à la demande, fermée par un try-avec-ressources",
                        List.of("try (LazyApplication application = LazyApplication.opening(home, () -> opener.open(home)))",
                                "MinosApplication::open, MinosLauncher::run));")));
        OWNERS.put("minos-cli/src/main/java/com/minos/cli/MinosCliRunner.java",
                new Owner(1, "run(Path, …) : l'ouverture est différée dans une LazyApplication, fermée par un try-avec-ressources",
                        List.of("try (LazyApplication application = LazyApplication.opening(home, () -> MinosApplication.open(home)))")));

        // La LazyApplication ne crée rien elle-même (l'ouvreur lui est donné) : elle ferme ce qu'elle a ouvert, une fois.
        CLOSERS.put("minos-cli/src/main/java/com/minos/cli/LazyApplication.java",
                List.of("if (owned && application != null) application.close();", "if (closed) return;"));

        for (String facade : List.of("LocalMinosApi", "LocalMinosMultiRepositoryApi", "LocalProviderPlatformApi")) {
            CLOSERS.put("minos-api/src/main/java/com/minos/api/" + facade + ".java",
                    List.of("openApplication(home", "if (!ownsApplication) return;", "application.close()"));
        }
    }

    @Test
    void everyApplicationCreatedInProductionHasAnOwnerThatClosesIt() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : productionSources(modules())) {
            String relative = relative(file);
            String code = codeOnly(Files.readString(file, StandardCharsets.UTF_8));
            Owner owner = OWNERS.get(relative);
            int creations = 0;
            Matcher creation = CREATION.matcher(code);
            while (creation.find()) {
                creations++;
                if (owner == null && !RESOURCE_HEADER.matcher(statementBefore(code, creation.start())).find()) {
                    offenders.add(relative + " : " + creation.group().strip() + " hors d'un try-avec-ressources");
                }
            }
            if (owner != null && creations != owner.creations()) {
                offenders.add(relative + " : " + creations + " création(s) au lieu des " + owner.creations()
                        + " déclarée(s) : un site nouveau dans un propriétaire n'est pas couvert");
            }
        }
        assertEquals(List.of(), offenders,
                "une application est ouverte sans propriétaire qui la ferme : try-avec-ressources, ou propriétaire déclaré");
    }

    @Test
    void everyDeclaredOwnerStillClosesItsApplication() throws IOException {
        List<String> stale = new ArrayList<>();
        for (Map.Entry<String, Owner> owner : OWNERS.entrySet()) {
            requireFragments(owner.getKey(), owner.getValue().proof(), stale);
        }
        for (Map.Entry<String, List<String>> closer : CLOSERS.entrySet()) {
            requireFragments(closer.getKey(), closer.getValue(), stale);
        }
        assertEquals(List.of(), stale);
    }

    /** Chaque fragment doit exister dans le code du fichier : commentaires retirés, espaces normalisés. */
    private static void requireFragments(String file, List<String> fragments, List<String> stale) throws IOException {
        String code = codeOnly(Files.readString(ROOT.resolve(file), StandardCharsets.UTF_8)).replaceAll("\\s+", " ");
        for (String fragment : fragments) {
            if (!code.contains(fragment)) {
                stale.add(file + " : la preuve de fermeture « " + fragment + " » a disparu du code");
            }
        }
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
