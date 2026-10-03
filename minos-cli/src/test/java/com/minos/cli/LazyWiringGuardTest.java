package com.minos.cli;

import com.minos.application.MinosApplication;
import com.minos.registry.RegisteredProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Q21, Q22 : rien n'est ouvert ni construit avant que la commande et ses options ne soient comprises, et une
 * commande de lecture ne laisse aucune trace de son câblage sous {@code MINOS_HOME}.
 *
 * <p>Les sous-commandes sont énumérées depuis la table de routes de {@link MinosCli} (jamais une liste recopiée) et
 * chacune doit être <em>classée</em> ci-dessous : une commande ajoutée sans classement fait échouer la suite, ce qui
 * est la raison d'être de cette garde. Chaque commande est lancée par le vrai lanceur, avec un ouvreur-espion qui
 * ouvre le vrai stockage.</p>
 */
class LazyWiringGuardTest {

    static final String PROJECT = "<project>";
    static final String ROOT = "<root>";

    /** Commandes qui ne font que muter : aucune de leurs formes valides n'est une lecture. */
    private static final Set<String> MUTATION_ONLY = Set.of("index", "import-scip", "remote");

    /**
     * Les formes de lecture, avec des arguments valides, de chaque commande qui en a une. Les formes de mutation
     * des commandes mixtes ({@code project add}, {@code tools install}, {@code runtime import}, les écritures de
     * {@code team} et {@code ide semantic-index-sync}) n'y figurent pas : elles écrivent légitimement.
     *
     * <p>Visible du paquet : {@code PartialResultCommandsContractTest} y lit les commandes de lecture de la table, pour
     * ne pas les recopier.</p>
     */
    static Map<String, List<String>> readInvocations() {
        Map<String, List<String>> reads = new LinkedHashMap<>();
        reads.put("project", List.of("project list", "project list --format json", "project inspect " + PROJECT));
        reads.put("inspect", List.of("inspect " + PROJECT));
        reads.put("index-status", List.of("index-status " + PROJECT));
        reads.put("tools", List.of("tools list"));
        reads.put("doctor", List.of("doctor"));
        reads.put("providers", List.of("providers", "providers --format json"));
        reads.put("architecture", List.of("architecture " + PROJECT));
        reads.put("impact", List.of("impact " + PROJECT + " S"));
        reads.put("git-activity", List.of("git-activity " + PROJECT));
        reads.put("nexus-export", List.of("nexus-export --root " + ROOT));
        reads.put("runtime", List.of("runtime sessions " + PROJECT, "runtime report " + PROJECT));
        reads.put("team", List.of("team tenant", "team workspaces", "team members"));
        reads.put("ide", List.of("ide handshake", "ide semantic-index-status " + PROJECT,
                "ide program-graph " + PROJECT));
        reads.put("find-symbol", List.of("find-symbol " + PROJECT + " S"));
        reads.put("search", List.of("search " + PROJECT + " query"));
        reads.put("get-source", List.of("get-source " + PROJECT + " Missing.java"));
        reads.put("find-usages", List.of("find-usages " + PROJECT + " S"));
        for (RelationshipCommand.Operation operation : RelationshipCommand.Operation.values()) {
            reads.put(operation.commandName(), List.of(operation.commandName() + " " + PROJECT + " S"));
        }
        reads.put("semantic", List.of("semantic status " + PROJECT));
        reads.put("hybrid", List.of("hybrid status " + PROJECT));
        return reads;
    }

    @TempDir Path temp;

    private record Launch(int exit, String output, String error, List<String> opened) { }

    /** Lance la ligne de commande par le vrai lanceur, sur le vrai stockage, en notant chaque ouverture de l'application. */
    private Launch launch(Path home, String... arguments) {
        return launch(home, MinosApplication::open, arguments);
    }

    /** Idem avec l'ouvreur donné : chaque demande d'ouverture est notée avant d'être transmise. */
    private Launch launch(Path home, MinosLauncher.ApplicationOpener opener, String... arguments) {
        List<String> opened = new ArrayList<>();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        int exit;
        try (PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
             PrintStream err = new PrintStream(error, true, StandardCharsets.UTF_8)) {
            exit = MinosLauncher.launch(arguments,
                    Map.of(MinosLauncher.HOME_ENVIRONMENT_VARIABLE, home.toString()), new Properties(), out, err,
                    resolved -> {
                        opened.add(resolved.toString());
                        return opener.open(resolved);
                    },
                    MinosLauncher::run);
        }
        return new Launch(exit, output.toString(StandardCharsets.UTF_8), error.toString(StandardCharsets.UTF_8), opened);
    }

    /** Les noms de commandes de la table de routes, plus {@code mcp} que le lanceur route avant elle. */
    private static Set<String> commandNames() {
        Set<String> names = new LinkedHashSet<>(MinosCliRunner.statelessHelpCommands());
        names.add("mcp");
        return names;
    }

    @Test
    void everyCommandOfTheDispatcherIsClassified() {
        Set<String> classified = new LinkedHashSet<>(readInvocations().keySet());
        classified.addAll(MUTATION_ONLY);
        classified.add("mcp");
        Set<String> unclassified = new LinkedHashSet<>(commandNames());
        unclassified.removeAll(classified);
        assertEquals(Set.of(), unclassified,
                "commande(s) de la table de routes absente(s) de cette garde : la classer en lecture "
                        + "(readInvocations) ou en mutation (MUTATION_ONLY)");
        Set<String> stale = new LinkedHashSet<>(classified);
        stale.removeAll(commandNames());
        assertEquals(Set.of(), stale, "entrée(s) de la garde qui ne sont plus des commandes de la table de routes");
        for (String mutation : MUTATION_ONLY) {
            assertTrue(!readInvocations().containsKey(mutation), mutation + " est classée à la fois lecture et mutation");
        }
    }

    @Test
    void aUsageErrorOpensNothingForEveryCommand() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (String command : commandNames()) {
            Path home = temp.resolve("usage-" + command);
            Launch launch = launch(home, command, "--bogus", "--bogus");
            List<String> problems = new ArrayList<>();
            if (launch.exit() != 2) problems.add("code " + launch.exit() + " au lieu de 2 (" + firstLine(launch.error()) + ")");
            if (!launch.opened().isEmpty()) problems.add("MINOS_HOME ouvert avant l'analyse");
            if (!HomeTree.of(home).isEmpty() || Files.exists(home)) {
                problems.add("a créé " + HomeTree.of(home).keySet());
            }
            if (!problems.isEmpty()) offenders.add(command + " --bogus --bogus : " + String.join("; ", problems));
        }
        assertEquals(List.of(), offenders, "une erreur d'usage doit sortir en 2 sans rien ouvrir ni créer");
    }

    @Test
    void helpAtAnyPositionOpensNothingForEveryCommand() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (String command : commandNames()) {
            for (int position = 1; position <= 5; position++) {
                List<String> arguments = new ArrayList<>();
                arguments.add(command);
                for (int index = 1; index < position; index++) arguments.add("operand" + index);
                arguments.add("--help");
                Path home = temp.resolve("help-" + command + "-" + position);
                Launch launch = launch(home, arguments.toArray(String[]::new));
                List<String> problems = new ArrayList<>();
                if (launch.exit() != 0) problems.add("code " + launch.exit() + " (" + firstLine(launch.error()) + ")");
                if (launch.output().isBlank()) problems.add("aucun usage affiché");
                if (!launch.opened().isEmpty()) problems.add("MINOS_HOME ouvert");
                if (Files.exists(home)) problems.add("a créé " + HomeTree.of(home).keySet());
                if (!problems.isEmpty()) offenders.add(String.join(" ", arguments) + " : " + String.join("; ", problems));
            }
        }
        assertEquals(List.of(), offenders, "--help est reconnu à toute position et reste sans état");
    }

    @Test
    void aReadCommandLeavesTheHomeTreeUntouched() throws Exception {
        Path home = temp.resolve("home");
        Path projectRoot = Files.createDirectories(temp.resolve("project"));
        Files.writeString(projectRoot.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion><groupId>demo</groupId><artifactId>demo</artifactId>
                <version>1</version></project>
                """);
        Files.writeString(Files.createDirectories(projectRoot.resolve("src/main/java/demo")).resolve("Demo.java"),
                "package demo; public class Demo { }\n");
        String projectId;
        try (MinosApplication application = MinosApplication.open(home)) {
            RegisteredProject project = application.projectRegistry().registerProject(projectRoot, "demo");
            projectId = project.id().toString();
            // Un home « initialisé » : ouvert, un projet enregistré, et le registre lu une fois (toute lecture du
            // registre prend son verrou inter-processus, qu'elle crée : c'est le stockage, pas le câblage).
            application.projectRegistry().listProjects();
        }
        // Et un home sur lequel la sonde du bac à sable des workers a déjà tourné : sous Windows, elle matérialise
        // sandbox/ (script AppContainer) la première fois qu'une commande qualifie le runtime, c'est la sonde et non
        // le câblage (voir « à traiter plus tard » dans CLI-SUIVI.md). Les commandes sont ensuite comparées à ce home.
        launch(home, "doctor");
        Map<String, String> initial = HomeTree.of(home);

        List<String> offenders = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : readInvocations().entrySet()) {
            for (String invocation : entry.getValue()) {
                String[] arguments = invocation.replace(PROJECT, projectId).replace(ROOT, projectRoot.toString()).split(" ");
                Launch launch = launch(home, arguments);
                String difference = HomeTree.difference(initial, HomeTree.of(home));
                if (!difference.isEmpty()) {
                    offenders.add(invocation + " (code " + launch.exit() + ") a modifié MINOS_HOME :" + difference);
                    // Chaque cas est mesuré contre le home initial : on le rétablit pour ne pas accuser le suivant.
                    restore(home, initial);
                }
            }
        }
        assertEquals(List.of(), offenders, "une commande de lecture ne crée ni ne modifie rien sous MINOS_HOME");
    }

    /** Supprime ce qu'une commande a créé en plus de {@code initial} (les fichiers modifiés sont signalés, non réparés). */
    private static void restore(Path home, Map<String, String> initial) throws IOException {
        Map<String, String> now = HomeTree.of(home);
        List<String> created = new ArrayList<>(now.keySet());
        created.removeAll(initial.keySet());
        created.sort((left, right) -> right.length() - left.length());
        for (String path : created) {
            Files.deleteIfExists(home.resolve(path.endsWith("/") ? path.substring(0, path.length() - 1) : path));
        }
    }

    @Test
    void anOpenFailureIsStillReportedAsABootstrapFailureByEveryCommandThatNeedsTheApplication() {
        List<String> offenders = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : readInvocations().entrySet()) {
            for (String invocation : entry.getValue()) {
                String[] arguments = invocation.replace(PROJECT, "p").replace(ROOT, temp.toString()).split(" ");
                Launch launch = launch(temp.resolve("refused"), resolved -> {
                    throw new IOException("home refused");
                }, arguments);
                boolean neededNoApplication = launch.opened().isEmpty() && launch.exit() == 0;
                boolean reportedAsBefore = !launch.opened().isEmpty() && launch.exit() == 1
                        && launch.error().strip().equals("error: MINOS bootstrap failed: home refused");
                if (!neededNoApplication && !reportedAsBefore) {
                    offenders.add(invocation + " -> code " + launch.exit() + ", " + launch.opened().size()
                            + " tentative(s) d'ouverture, '" + firstLine(launch.error()) + "'");
                }
            }
        }
        assertEquals(List.of(), offenders,
                "un MINOS_HOME qui ne s'ouvre pas reste « MINOS bootstrap failed » (code 1), jamais un échec de la commande");
    }

    private static String firstLine(String text) {
        return text.lines().findFirst().orElse("");
    }
}
