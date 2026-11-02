package com.minos.adapter.scip.runtime.plan;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@link ScipJavaMavenOffline#prepare} gives the scip-java plan, which calls it before staging the project.
 * Outside Windows the scip-java plan builds offline from the designated Maven repository (ADR SEC-0076): fixed
 * Maven line after {@code --}, the repository as the only read-only root, the host Maven settings masked, constant
 * run settings and a fresh head repository; every designation that would expose a sensitive directory, and every
 * unqualified Maven, is refused before the project is staged, without a path in the message.
 */
@EnabledOnOs(OS.LINUX)
class ScipJavaMavenOfflineTest {

    @TempDir Path temp;

    private Path project;
    private Path run;

    @BeforeEach
    void projectAndRun() throws IOException {
        project = Files.createDirectories(temp.resolve("project"));
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        run = Files.createDirectories(temp.resolve("run")).toAbsolutePath().normalize();
    }

    @Test
    void theRunGetsTheFixedOfflineLineTheRepositoryAsOnlyRootAndTheHostSettingsMasked() throws Exception {
        Path fixture = temp.resolve("maven");
        ScipJavaMavenOffline.RunConfiguration plan = ScipJavaMavenOfflineFixtures.qualified(fixture).prepare(project, run);

        Path repository = fixture.resolve("repository").toRealPath();
        Path settings = run.resolve("maven").resolve("settings.xml");
        Path head = run.resolve("maven").resolve("local");
        assertEquals(List.of(
                "--batch-mode", "--offline",
                "-s", settings.toString(), "-gs", settings.toString(),
                "-Dmaven.repo.local=" + head, "-Dmaven.repo.local.tail=" + repository,
                "clean", "verify", "-DskipTests"), plan.mavenArguments());
        assertEquals(List.of(repository), plan.readOnlyRoots(), "the designated repository is the only read-only root");
        Path conf = fixture.resolve("apache-maven-3.9.16").toRealPath().resolve("conf");
        assertEquals(List.of(conf.resolve("settings.xml"), conf.resolve("toolchains.xml")), plan.maskedFiles());
        assertArrayEquals(ScipJavaMavenOffline.SETTINGS_XML.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(settings),
                "the run settings are the constant content, byte for byte");
        assertTrue(Files.isDirectory(head));
        try (var entries = Files.list(head)) {
            assertEquals(0, entries.count(), "the head repository is created empty");
        }
        assertFalse(Files.exists(fixture.resolve("apache-maven-3.9.16/bin/mvn.executed")), "Maven is never executed");
    }

    @Test
    void theConstantSettingsDeclareNoServerMirrorProxyProfileLocalRepositoryOrOfflineFlag() {
        for (String element : List.of("<servers", "<mirrors", "<proxies", "<profiles", "<localRepository", "<offline")) {
            assertFalse(ScipJavaMavenOffline.SETTINGS_XML.contains(element), element);
        }
    }

    @Test
    void theUserSettingsBesideTheRepositoryAreNeverNamed() throws Exception {
        Path fixture = temp.resolve("maven");
        Path userMavenDirectory = Files.createDirectories(fixture.resolve("user-home/.m2"));
        Path userSettings = Files.writeString(userMavenDirectory.resolve("settings.xml"),
                "<settings><servers><server><password>secret</password></server></servers></settings>");
        Path repository = Files.createDirectories(userMavenDirectory.resolve("repository/org"));

        ScipJavaMavenOffline.RunConfiguration plan = ScipJavaMavenOfflineFixtures.designating(
                fixture, repository.getParent().toString()).prepare(project, run);

        assertEquals(List.of(repository.getParent().toRealPath()), plan.readOnlyRoots());
        assertFalse(plan.mavenArguments().contains(userMavenDirectory.toString()), plan.mavenArguments()::toString);
        assertFalse(plan.mavenArguments().stream().anyMatch(argument -> argument.contains(userSettings.toString())),
                plan.mavenArguments()::toString);
        assertFalse(plan.readOnlyRoots().contains(userMavenDirectory.toRealPath()));
    }

    @Test
    void withoutADesignationThePlanIsRefusedBeforeTheProjectIsStaged() throws Exception {
        IllegalStateException refused = refusedBy(ScipJavaMavenOfflineFixtures.designating(temp.resolve("maven"), null));

        assertTrue(refused.getMessage().contains(ScipJavaMavenOffline.REPOSITORY_SETTING), refused.getMessage());
    }

    @Test
    void aRelativeAnAbsentOrALinkedDesignationIsRefused() throws Exception {
        Path fixture = temp.resolve("maven");
        Path repository = ScipJavaMavenOfflineFixtures.repository(fixture);
        Path link = Files.createSymbolicLink(temp.resolve("linked-repository"), repository);

        refusedBy(ScipJavaMavenOfflineFixtures.designating(fixture, "relative/repository"));
        refusedBy(ScipJavaMavenOfflineFixtures.designating(fixture, temp.resolve("absent").toString()));
        refusedBy(ScipJavaMavenOfflineFixtures.designating(fixture, link.toString()));
    }

    @Test
    void theHomeDirectoryItsAncestorsMinosHomeAndSystemDirectoriesAreRefused() throws Exception {
        Path fixture = temp.resolve("maven");
        Path userHome = Files.createDirectories(fixture.resolve("user-home"));
        Path minosHome = Files.createDirectories(fixture.resolve("minos-home"));
        Path insideMinosHome = Files.createDirectories(minosHome.resolve("tools/repository"));

        for (Path designation : List.of(userHome, userHome.getParent(), Path.of("/"), Path.of("/etc"),
                minosHome, insideMinosHome)) {
            refusedBy(ScipJavaMavenOfflineFixtures.designating(fixture, designation.toString()));
        }
    }

    @Test
    void theRealDirectoryOfAHomeReachedThroughALinkIsRefused() throws Exception {
        Path fixture = temp.resolve("maven");
        Path mvn = ScipJavaMavenOfflineFixtures.fakeMaven(fixture, "3.9.16");
        Path realHomes = Files.createDirectories(temp.resolve("data/home/alice/projects"));
        Path linkedHomes = Files.createSymbolicLink(temp.resolve("home"), realHomes.getParent().getParent());
        Path userHome = linkedHomes.resolve("alice");

        for (Path designation : List.of(realHomes.getParent().getParent(), realHomes.getParent())) {
            refusedBy(ScipJavaMavenOffline.of(designation.toString(), Files.createDirectories(fixture.resolve("home")),
                    userHome, mvn, List.of(temp.toRealPath())));
        }
    }

    @Test
    void aHiddenDirectoryIsRefusedEvenWhenItHoldsOnlyDirectories() throws Exception {
        Path config = Files.createDirectories(temp.resolve("profile/.config/gh"));
        refusedBy(ScipJavaMavenOfflineFixtures.designating(temp.resolve("maven"), config.getParent().toString()));
    }

    @Test
    void aDesignationThatIsNotShapedAsAMavenRepositoryIsRefused() throws Exception {
        Path fixture = temp.resolve("maven");
        Path withSettings = Files.createDirectories(temp.resolve("with-settings"));
        Files.writeString(withSettings.resolve("settings-security.xml"), "<settingsSecurity/>");
        Path withSsh = Files.createDirectories(temp.resolve("with-ssh/.ssh"));
        Path withFile = Files.createDirectories(temp.resolve("with-file"));
        Files.writeString(withFile.resolve("notes.txt"), "not maven metadata");
        Path mavenUserDirectory = Files.createDirectories(temp.resolve("profile/.m2"));

        for (Path designation : List.of(withSettings, withSsh.getParent(), withFile, mavenUserDirectory)) {
            refusedBy(ScipJavaMavenOfflineFixtures.designating(fixture, designation.toString()));
        }
    }

    @Test
    void aDesignationThatOverlapsTheRunOrTheProjectIsRefused() throws Exception {
        Path inProject = Files.createDirectories(project.resolve("repository"));
        for (Path designation : List.of(run, inProject)) {
            IllegalStateException refused = refusedBy(
                    ScipJavaMavenOfflineFixtures.designating(temp.resolve("maven"), designation.toString()));
            assertTrue(refused.getMessage().contains("overlaps"), refused.getMessage());
        }
    }

    @Test
    void aWindowsProfileSeenFromWslAndItsAncestorsAreRecognised() {
        for (String path : List.of("/mnt", "/mnt/c", "/mnt/c/Users", "/mnt/c/Users/alice", "/mnt/d/users/bob")) {
            assertTrue(ScipJavaMavenOffline.isWindowsProfileOrAncestor(Path.of(path)), path);
        }
        assertFalse(ScipJavaMavenOffline.isWindowsProfileOrAncestor(Path.of("/mnt/c/Users/alice/.m2/repository")));
        assertFalse(ScipJavaMavenOffline.isWindowsProfileOrAncestor(Path.of("/mnt/c/maven-repository")));
        assertFalse(ScipJavaMavenOffline.isWindowsProfileOrAncestor(Path.of("/srv/maven")));
    }

    @Test
    void anExistingHeadRepositoryIsRefused() throws Exception {
        Files.createDirectories(run.resolve("maven/local"));

        ScipJavaMavenOffline offline = ScipJavaMavenOfflineFixtures.qualified(temp.resolve("maven"));
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> offline.prepare(project, run));

        assertTrue(refused.getMessage().contains("head Maven repository"), refused.getMessage());
        assertFalse(Files.exists(run.resolve("maven/settings.xml")), "no settings are written for a refused run");
    }

    @Test
    void maven4AndMaven38AreRefusedNamingTheQualifiedRangeWithoutRunningMaven() throws Exception {
        for (String version : List.of("4.0.0", "3.8.8")) {
            Path fixture = temp.resolve("maven-" + version);
            IllegalStateException refused = refusedBy(ScipJavaMavenOfflineFixtures.withMaven(fixture, version));

            assertTrue(refused.getMessage().contains(ScipJavaMavenOffline.QUALIFIED_MAVEN_RANGE), refused.getMessage());
            assertFalse(Files.exists(fixture.resolve("apache-maven-" + version + "/bin/mvn.executed")));
        }
    }

    @Test
    void aMavenOutsideTheSandboxSystemRootsOrAbsentIsRefused() throws Exception {
        Path fixture = temp.resolve("maven");
        Path mvn = ScipJavaMavenOfflineFixtures.fakeMaven(fixture, "3.9.16");
        Path repository = ScipJavaMavenOfflineFixtures.repository(fixture);
        Path minosHome = Files.createDirectories(fixture.resolve("minos-home"));

        refusedBy(ScipJavaMavenOffline.of(repository.toString(), minosHome, null, mvn, List.of(Path.of("/usr"))));
        refusedBy(ScipJavaMavenOffline.of(repository.toString(), minosHome, null, null, List.of(fixture)));
    }

    @Test
    void theVersionIsReadFromTheMavenCoreJarName() throws Exception {
        Path fixture = temp.resolve("maven");
        ScipJavaMavenOfflineFixtures.fakeMaven(fixture, "3.9.16");

        assertEquals("3.9.16", ScipJavaMavenOffline.coreVersion(fixture.resolve("apache-maven-3.9.16")).orElseThrow());
        assertTrue(ScipJavaMavenOffline.coreVersion(temp.resolve("no-maven")).isEmpty());
    }

    private IllegalStateException refusedBy(ScipJavaMavenOffline offline) {
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> offline.prepare(project, run));
        assertFalse(refused.getMessage().contains(temp.toString()), refused.getMessage());
        assertFalse(Files.exists(run.resolve("maven")), "a refused run writes no Maven settings");
        return refused;
    }
}
