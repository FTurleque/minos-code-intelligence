package com.minos.adapter.scip.runtime.plan;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Offline Maven builds for tests: a fake Maven installation (never executed, its version only read from the name of
 * its {@code lib/maven-core} jar), a designated repository and a MINOS home, all under one directory that stands for
 * the sandbox system roots. Nothing is read from the host.
 */
public final class ScipJavaMavenOfflineFixtures {

    private ScipJavaMavenOfflineFixtures() {
    }

    /** The build of a host where nothing is designated and no Maven is found. */
    public static ScipJavaMavenOffline unconfigured(Path minosHome) {
        return ScipJavaMavenOffline.configured(null, minosHome, Optional::empty);
    }

    /** A qualified build: Maven 3.9.16 and a valid designated repository under {@code root}. */
    public static ScipJavaMavenOffline qualified(Path root) throws IOException {
        Path mvn = fakeMaven(root, "3.9.16");
        Path repository = repository(root);
        return ScipJavaMavenOffline.of(repository.toString(), minosHome(root), root.resolve("user-home"), mvn,
                List.of(root.toRealPath()));
    }

    /** A build whose repository designation is {@code designation} (null: unset) with a qualified Maven. */
    public static ScipJavaMavenOffline designating(Path root, String designation) throws IOException {
        Path mvn = fakeMaven(root, "3.9.16");
        return ScipJavaMavenOffline.of(designation, minosHome(root), root.resolve("user-home"), mvn,
                List.of(root.toRealPath()));
    }

    /** A build with a valid repository and the Maven {@code version}. */
    public static ScipJavaMavenOffline withMaven(Path root, String version) throws IOException {
        Path mvn = fakeMaven(root, version);
        return ScipJavaMavenOffline.of(repository(root).toString(), minosHome(root), root.resolve("user-home"), mvn,
                List.of(root.toRealPath()));
    }

    /**
     * A fake Maven {@code version} installation under {@code root}; its launcher leaves {@code mvn.executed} beside
     * itself and fails if it is ever run.
     */
    public static Path fakeMaven(Path root, String version) throws IOException {
        Path home = Files.createDirectories(root.resolve("apache-maven-" + version));
        Path bin = Files.createDirectories(home.resolve("bin"));
        Path mvn = bin.resolve("mvn");
        if (!Files.exists(mvn)) {
            Files.writeString(mvn, "#!/bin/sh\ntouch \"$0.executed\"\nexit 97\n");
            mvn.toFile().setExecutable(true);
        }
        Path lib = Files.createDirectories(home.resolve("lib"));
        Files.writeString(lib.resolve("maven-core-" + version + ".jar"), "jar");
        Path conf = Files.createDirectories(home.resolve("conf"));
        Files.writeString(conf.resolve("settings.xml"), "<settings><servers><server><password>secret</password>"
                + "</server></servers></settings>\n");
        return mvn;
    }

    /** A designated repository laid out as a Maven repository. */
    public static Path repository(Path root) throws IOException {
        Path repository = Files.createDirectories(root.resolve("repository"));
        Files.createDirectories(repository.resolve("org").resolve("apache"));
        return repository.toRealPath();
    }

    private static Path minosHome(Path root) throws IOException {
        return Files.createDirectories(root.resolve("minos-home"));
    }
}
