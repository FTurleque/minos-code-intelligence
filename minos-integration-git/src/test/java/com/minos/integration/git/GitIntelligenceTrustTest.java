package com.minos.integration.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.StoredConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.UserPrincipal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Un dépôt analysé n'est pas de confiance : aucune commande qu'il déclare ne s'exécute pendant
 * {@code inspect}/{@code analyze}, et un dépôt d'un autre propriétaire n'est pas ouvert.
 *
 * <p>Le filtre piégé est {@link TrustWitness} lancé par {@code java} (aucun {@code sh}, aucun
 * {@code cmd} dans le test) ; le fichier témoin prouve l'exécution.</p>
 */
class GitIntelligenceTrustTest {

    private static final String TRACKED = "tracked.txt";
    private static final String ATTRIBUTE_LINE = "* filter=piege\n";

    @Test
    void aTrapFilterInTheRepositoryConfigurationIsNotExecuted(@TempDir Path temp) throws Exception {
        Path root = repositoryWithSmudgedFile(temp.resolve("repo"));
        Path witness = temp.resolve("witness-config");
        declareFilter(root, witness);
        Files.writeString(root.resolve(".gitattributes"), ATTRIBUTE_LINE);
        byte[] configBefore = Files.readAllBytes(root.resolve(".git/config"));

        GitIntelligenceService.RepositoryView view = new GitIntelligenceService().inspect(root);

        assertFalse(Files.exists(witness), "le filtre du dépôt a été exécuté");
        org.junit.jupiter.api.Assertions.assertArrayEquals(
                configBefore, Files.readAllBytes(root.resolve(".git/config")), ".git/config a été modifié");
        assertTrue(view.limitations().contains("REPOSITORY_FILTERS_NOT_APPLIED"), view.limitations().toString());
    }

    @Test
    void aTrapFilterReachedThroughAnIncludedFileIsNotExecuted(@TempDir Path temp) throws Exception {
        Path root = repositoryWithSmudgedFile(temp.resolve("repo"));
        Path witness = temp.resolve("witness-include");
        Path included = temp.resolve("included.cfg");
        Files.writeString(included, "[filter \"piege\"]\n\tclean = " + command(witness) + "\n");
        StoredConfig config = repositoryConfig(root);
        config.setString("include", null, "path", included.toString().replace('\\', '/'));
        config.save();
        Files.writeString(root.resolve(".gitattributes"), ATTRIBUTE_LINE);

        new GitIntelligenceService().inspect(root);

        assertFalse(Files.exists(witness), "le filtre d'un fichier inclus a été exécuté");
    }

    @Test
    void anAttributeDeclaredInInfoAttributesIsNotExecuted(@TempDir Path temp) throws Exception {
        Path root = repositoryWithSmudgedFile(temp.resolve("repo"));
        Path witness = temp.resolve("witness-info");
        declareFilter(root, witness);
        Files.createDirectories(root.resolve(".git/info"));
        Files.writeString(root.resolve(".git/info/attributes"), ATTRIBUTE_LINE);

        new GitIntelligenceService().inspect(root);

        assertFalse(Files.exists(witness), "le filtre désigné par info/attributes a été exécuté");
    }

    @Test
    void aParentRepositoryTrapIsNotExecutedWhenTheProjectIsBelowIt(@TempDir Path temp) throws Exception {
        Path root = repositoryWithSmudgedFile(temp.resolve("parent"));
        Path witness = temp.resolve("witness-parent");
        declareFilter(root, witness);
        Files.writeString(root.resolve(".gitattributes"), ATTRIBUTE_LINE);
        Path project = Files.createDirectories(root.resolve("service-a"));

        new GitIntelligenceService().inspect(project);

        assertFalse(Files.exists(witness), "le filtre du dépôt parent a été exécuté");
    }

    @Test
    void aRepositoryShippedInsideTheProjectIsNotExecutedByAnalyze(@TempDir Path temp) throws Exception {
        Path project = repositoryWithSmudgedFile(temp.resolve("project"));
        Path witness = temp.resolve("witness-shipped");
        declareFilter(project, witness);
        Files.writeString(project.resolve(".gitattributes"), ATTRIBUTE_LINE);

        GitIntelligenceService.ActivityReport report = new GitIntelligenceService().analyze(
                project, new GitIntelligenceService.ActivityQuery(Instant.EPOCH, 100, 100, 1));

        assertFalse(Files.exists(witness), "le filtre du dépôt livré avec le projet a été exécuté");
        assertEquals(1, report.scannedCommitCount());
    }

    @Test
    void aFilterOfTheUserConfigurationIsStillApplied(@TempDir Path temp) throws Exception {
        Path root = repositoryWithSmudgedFile(temp.resolve("repo"));
        Path witness = temp.resolve("witness-user");
        Path home = Files.createDirectories(temp.resolve("home"));
        Files.writeString(home.resolve(".gitconfig"),
                "[filter \"piege\"]\n\tclean = " + command(witness) + "\n");
        Files.writeString(root.resolve(".gitattributes"), ATTRIBUTE_LINE);

        ProcessBuilder builder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Duser.home=" + home,
                "-cp", System.getProperty("java.class.path"),
                UserConfigurationProbe.class.getName(), root.toString());
        builder.environment().put("HOME", home.toString());
        builder.environment().put("USERPROFILE", home.toString());
        builder.environment().put("XDG_CONFIG_HOME", home.resolve("xdg").toString());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertEquals(0, process.waitFor(), output);
        assertTrue(Files.exists(witness), "le filtre de la configuration de l'utilisateur n'est plus appliqué: " + output);
    }

    /** Runs in a child JVM so that the user configuration really is the redirected one. */
    public static final class UserConfigurationProbe {
        public static void main(String[] arguments) throws Exception {
            new GitIntelligenceService().inspect(Path.of(arguments[0]));
        }
    }

    @Test
    void aRepositoryOfAnotherOwnerIsRefusedBeforeAnyStatus(@TempDir Path temp) throws Exception {
        Path root = repositoryWithSmudgedFile(temp.resolve("repo"));
        Path witness = temp.resolve("witness-owner");
        declareFilter(root, witness);
        Files.writeString(root.resolve(".gitattributes"), ATTRIBUTE_LINE);
        GitIntelligenceService service = new GitIntelligenceService(
                GitIntelligenceService.ActivityBudget.DEFAULT, () -> principal("autre-compte"));

        IOException refused = assertThrows(IOException.class, () -> service.inspect(root));

        assertTrue(refused.getMessage().contains("autre-compte"), refused.getMessage());
        assertTrue(refused.getMessage().contains(ownerName(root.resolve(".git"))), refused.getMessage());
        assertFalse(Files.exists(witness));
        assertFalse(refused.getMessage().contains(temp.toString()), "chemin absolu dans le message");
    }

    @Test
    void anUnreadableOwnerFailsClosedWithADistinctMessage(@TempDir Path temp) throws Exception {
        Path root = repositoryWithSmudgedFile(temp.resolve("repo"));
        GitIntelligenceService service = new GitIntelligenceService(
                GitIntelligenceService.ActivityBudget.DEFAULT, () -> {
                    throw new java.io.UncheckedIOException(new IOException("pas de propriétaire"));
                });

        IOException unreadable = assertThrows(IOException.class, () -> service.inspect(root));
        IOException different = assertThrows(IOException.class, () -> new GitIntelligenceService(
                GitIntelligenceService.ActivityBudget.DEFAULT, () -> principal("autre-compte")).inspect(root));

        assertTrue(unreadable.getMessage().contains("cannot be read"), unreadable.getMessage());
        org.junit.jupiter.api.Assertions.assertNotEquals(different.getMessage(), unreadable.getMessage());
    }

    @Test
    void aRepositoryOfTheExpectedOwnerIsAnalysedAsBefore(@TempDir Path temp) throws Exception {
        Path root = repositoryWithSmudgedFile(temp.resolve("repo"));
        UserPrincipal owner = Files.getOwner(root.resolve(".git"));

        GitIntelligenceService.RepositoryView explicit = new GitIntelligenceService(
                GitIntelligenceService.ActivityBudget.DEFAULT, () -> owner).inspect(root);
        GitIntelligenceService.RepositoryView byDefault = new GitIntelligenceService().inspect(root);

        assertTrue(explicit.clean());
        assertEquals(explicit.repositoryId(), byDefault.repositoryId());
        assertFalse(explicit.limitations().contains("REPOSITORY_ABOVE_PROJECT_ROOT"));
        assertFalse(explicit.limitations().contains("REPOSITORY_FILTERS_NOT_APPLIED"));
    }

    @Test
    void aProjectBelowTheRepositoryRootIsReportedAsSuch(@TempDir Path temp) throws Exception {
        Path root = repositoryWithSmudgedFile(temp.resolve("mono"));
        Path project = Files.createDirectories(root.resolve("service-a"));

        GitIntelligenceService.RepositoryView below = new GitIntelligenceService().inspect(project);
        GitIntelligenceService.RepositoryView atRoot = new GitIntelligenceService().inspect(root);

        assertEquals(root.toRealPath().toString(), below.workTree());
        assertTrue(below.limitations().contains("REPOSITORY_ABOVE_PROJECT_ROOT"), below.limitations().toString());
        assertFalse(atRoot.limitations().contains("REPOSITORY_ABOVE_PROJECT_ROOT"));
    }

    // -- aides ------------------------------------------------------------------------------

    /** Dépôt avec un fichier suivi dont la date est ensuite changée : le statut doit relire le contenu. */
    private static Path repositoryWithSmudgedFile(Path root) throws Exception {
        Files.createDirectories(root);
        try (Git git = Git.init().setDirectory(root.toFile()).call()) {
            Files.writeString(root.resolve(TRACKED), "contenu\n");
            git.add().addFilepattern(TRACKED).call();
            git.commit().setMessage("initial").setAuthor("Dev", "dev@example.com")
                    .setCommitter("Dev", "dev@example.com").call();
        }
        Files.setLastModifiedTime(root.resolve(TRACKED), FileTime.fromMillis(System.currentTimeMillis() + 60_000));
        return root;
    }

    private static void declareFilter(Path root, Path witness) throws IOException {
        Files.writeString(root.resolve(".git/config"),
                "[filter \"piege\"]\n\tclean = " + command(witness) + "\n",
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
    }

    private static StoredConfig repositoryConfig(Path root) throws IOException {
        try (Git git = Git.open(root.toFile())) {
            StoredConfig config = git.getRepository().getConfig();
            config.load();
            return config;
        } catch (org.eclipse.jgit.errors.ConfigInvalidException exception) {
            throw new IOException(exception);
        }
    }

    private static String command(Path witness) throws IOException {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString().replace('\\', '/');
        String classes;
        try {
            classes = Path.of(TrustWitness.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toString().replace('\\', '/');
        } catch (java.net.URISyntaxException exception) {
            throw new IOException(exception);
        }
        return "\"" + java + "\" -cp \"" + classes + "\" " + TrustWitness.class.getName()
                + " \"" + witness.toString().replace('\\', '/') + "\"";
    }

    private static String ownerName(Path path) throws IOException {
        return Files.getOwner(path).getName();
    }

    private static UserPrincipal principal(String name) {
        return new UserPrincipal() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String toString() {
                return name;
            }
        };
    }
}
