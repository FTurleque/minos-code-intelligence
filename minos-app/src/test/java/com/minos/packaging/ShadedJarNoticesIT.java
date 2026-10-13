package com.minos.packaging;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AUD-DEP-16 — le JAR distribué conserve les notices et les licences des dépendances qu'il embarque.
 * Les dépendances attendues ne sont pas une liste écrite ici : ce sont les JAR du classpath du test
 * dont une classe se retrouve dans le JAR ombré. Une dépendance ajoutée demain est donc contrôlée
 * sans modifier ce test.
 */
class ShadedJarNoticesIT {

    private static final String AGGREGATED_NOTICE = "META-INF/NOTICE";
    private static final String MINOS_LICENCE = "META-INF/LICENSE";
    private static final String LICENCES_DIRECTORY = "META-INF/licenses/";
    private static final String META_INF = "META-INF/";
    private static final Pattern LEGAL_FILE_NAME =
            Pattern.compile("notice|licen[sc]e|copying|^about\\.html$", Pattern.CASE_INSENSITIVE);

    private static Path shadedJar;
    private static Map<String, byte[]> shadedLegalEntries;
    private static List<Dependency> dependencies;

    /** Un JAR de dépendance embarqué et ses fichiers de notice ou de licence, par nom d'entrée. */
    private record Dependency(String name, Map<String, byte[]> legalFiles) {

        Map<String, byte[]> notices() {
            Map<String, byte[]> notices = new TreeMap<>();
            legalFiles.forEach((entry, content) -> {
                if (isTopLevel(entry) && fileName(entry).toLowerCase(Locale.ROOT).contains("notice")) {
                    notices.put(entry, content);
                }
            });
            return notices;
        }
    }

    @BeforeAll
    static void readTheShadedJarAndItsDependencies() throws Exception {
        shadedJar = Path.of(System.getProperty("minos.shaded.jar")).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(shadedJar), () -> "missing shaded JAR: " + shadedJar);
        Set<String> shadedEntries = new TreeSet<>();
        try (JarFile jar = new JarFile(shadedJar.toFile())) {
            shadedLegalEntries = legalFiles(jar);
            jar.stream().map(JarEntry::getName).forEach(shadedEntries::add);
        }
        dependencies = new ArrayList<>();
        for (Path candidate : classpathJars()) {
            try (JarFile jar = new JarFile(candidate.toFile())) {
                Optional<String> firstClass = jar.stream()
                        .map(JarEntry::getName)
                        .filter(name -> name.endsWith(".class") && !name.startsWith(META_INF)
                                && !name.endsWith("module-info.class"))
                        .findFirst();
                if (firstClass.isPresent() && shadedEntries.contains(firstClass.get())) {
                    String fileName = candidate.getFileName().toString();
                    dependencies.add(new Dependency(
                            fileName.substring(0, fileName.length() - ".jar".length()), legalFiles(jar)));
                }
            }
        }
        assertTrue(dependencies.size() > 1,
                () -> "the test classpath must expose the dependencies embedded in the shaded JAR: " + dependencies);
    }

    @Test
    void theAggregatedNoticeCarriesTheNoticeOfEveryEmbeddedDependency() {
        byte[] aggregated = shadedLegalEntries.get(AGGREGATED_NOTICE);
        assertTrue(aggregated != null && aggregated.length > 0, "the shaded JAR must carry a non-empty " + AGGREGATED_NOTICE);
        Set<String> aggregatedLines = new LinkedHashSet<>(significantLines(aggregated));

        List<String> missing = new ArrayList<>();
        Set<String> distinctNotices = new TreeSet<>();
        for (Dependency dependency : dependencies) {
            dependency.notices().forEach((entry, content) -> {
                distinctNotices.add(new String(content, StandardCharsets.UTF_8));
                for (String line : significantLines(content)) {
                    if (!aggregatedLines.contains(line)) {
                        missing.add(dependency.name() + "!/" + entry + ": " + line);
                    }
                }
            });
        }
        assertTrue(distinctNotices.size() > 1,
                "at least two embedded dependencies must provide different notices, otherwise nothing proves the aggregation");
        assertEquals(List.of(), missing, "notice lines of embedded dependencies absent from " + AGGREGATED_NOTICE);

        List<String> otherTopLevelNotices = shadedLegalEntries.keySet().stream()
                .filter(entry -> isTopLevel(entry) && !entry.equals(AGGREGATED_NOTICE)
                        && fileName(entry).toLowerCase(Locale.ROOT).startsWith("notice"))
                .toList();
        assertEquals(List.of(), otherTopLevelNotices,
                "every notice variant must be aggregated into " + AGGREGATED_NOTICE + ", not left beside it");
    }

    @Test
    void theAggregatedNoticeDoesNotAttributeMinosToTheApacheSoftwareFoundation() {
        byte[] aggregated = shadedLegalEntries.get(AGGREGATED_NOTICE);
        assertTrue(aggregated != null && aggregated.length > 0, "the shaded JAR must carry a non-empty " + AGGREGATED_NOTICE);
        Set<String> dependencyLines = new LinkedHashSet<>();
        for (Dependency dependency : dependencies) {
            dependency.notices().values().forEach(content -> dependencyLines.addAll(significantLines(content)));
        }
        // Une ligne qui ne vient d'aucune dépendance est écrite par l'ombrage, au nom de MINOS.
        List<String> apacheAttribution = significantLines(aggregated).stream()
                .filter(line -> !dependencyLines.contains(line))
                .filter(line -> line.toLowerCase(Locale.ROOT).contains("apache"))
                .toList();
        assertEquals(List.of(), apacheAttribution,
                "MINOS is proprietary software: what the shading writes in its name must not mention the Apache Software Foundation");
    }

    @Test
    void everyDependencyKeepsItsLicenceAndNoticeFilesUnderItsOwnDirectory() {
        List<String> failures = new ArrayList<>();
        Set<String> distinctLicences = new TreeSet<>();
        int dependenciesWithLegalFiles = 0;
        for (Dependency dependency : dependencies) {
            if (!dependency.legalFiles().isEmpty()) {
                dependenciesWithLegalFiles++;
            }
            for (Map.Entry<String, byte[]> legalFile : dependency.legalFiles().entrySet()) {
                // Un fichier déjà rangé par la dépendance sous un chemin qui lui est propre garde ce chemin.
                String expected = isTopLevel(legalFile.getKey())
                        ? LICENCES_DIRECTORY + dependency.name() + "/" + fileName(legalFile.getKey())
                        : legalFile.getKey();
                byte[] kept = shadedLegalEntries.get(expected);
                if (legalFile.getValue().length == 0) {
                    failures.add(dependency.name() + "!/" + legalFile.getKey() + " is empty");
                } else if (kept == null) {
                    failures.add(dependency.name() + "!/" + legalFile.getKey() + " is not kept as " + expected);
                } else if (!Arrays.equals(kept, legalFile.getValue())) {
                    failures.add(expected + " differs from " + dependency.name() + "!/" + legalFile.getKey());
                }
                if (!fileName(legalFile.getKey()).toLowerCase(Locale.ROOT).contains("notice")) {
                    distinctLicences.add(new String(legalFile.getValue(), StandardCharsets.UTF_8));
                }
            }
        }
        assertTrue(dependenciesWithLegalFiles > 1 && distinctLicences.size() > 1,
                "at least two embedded dependencies must provide different licence texts, otherwise nothing proves they are kept apart");
        assertEquals(List.of(), failures, "licence or notice files of embedded dependencies lost by the shading");
    }

    @Test
    void theTopLevelLicenceIsTheMinosLicence() {
        byte[] licence = shadedLegalEntries.get(MINOS_LICENCE);
        assertTrue(licence != null && licence.length > 0, "the shaded JAR must carry a non-empty " + MINOS_LICENCE);
        String text = new String(licence, StandardCharsets.UTF_8);
        assertTrue(text.contains("MINOS CODE INTELLIGENCE") && text.contains("PROPRIETARY"),
                MINOS_LICENCE + " must be the MINOS licence, not the licence of whichever dependency was shaded first");
        assertFalse(text.contains("Apache License"), MINOS_LICENCE + " must not present MINOS as Apache-licensed");

        List<String> ambiguous = shadedLegalEntries.keySet().stream()
                .filter(entry -> entry.startsWith(MINOS_LICENCE + ".") && isTopLevel(entry))
                .toList();
        assertEquals(List.of(), ambiguous,
                "a top-level licence file without a dependency name cannot be attributed: it belongs under " + LICENCES_DIRECTORY);
    }

    /** Lignes utiles d'une notice : sans blancs de bord, sans lignes vides ni commentaires {@code //}. */
    private static List<String> significantLines(byte[] content) {
        return new String(content, StandardCharsets.UTF_8).lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("//"))
                .toList();
    }

    private static Map<String, byte[]> legalFiles(JarFile jar) throws IOException {
        Map<String, byte[]> legalFiles = new TreeMap<>();
        for (JarEntry entry : Collections.list(jar.entries())) {
            String name = entry.getName();
            if (entry.isDirectory() || name.endsWith(".class") || !LEGAL_FILE_NAME.matcher(fileName(name)).find()) {
                continue;
            }
            if (!isTopLevel(name) && !name.startsWith(LICENCES_DIRECTORY)) {
                continue;
            }
            try (InputStream input = jar.getInputStream(entry)) {
                legalFiles.put(name, input.readAllBytes());
            }
        }
        return legalFiles;
    }

    /** À la racine du JAR ou directement sous {@code META-INF/} : là où les homonymes s'écrasent. */
    private static boolean isTopLevel(String entry) {
        String relative = entry.startsWith(META_INF) ? entry.substring(META_INF.length()) : entry;
        return relative.indexOf('/') < 0;
    }

    private static String fileName(String entry) {
        return entry.substring(entry.lastIndexOf('/') + 1);
    }

    /** JAR de dépendances visibles du chargeur du test, que Failsafe passe le classpath directement ou par un JAR manifeste. */
    private static Set<Path> classpathJars() throws IOException, URISyntaxException {
        Set<Path> jars = new LinkedHashSet<>();
        for (URL manifest : Collections.list(
                ShadedJarNoticesIT.class.getClassLoader().getResources(JarFile.MANIFEST_NAME))) {
            if (manifest.openConnection() instanceof JarURLConnection connection) {
                connection.setUseCaches(false);
                jars.add(Path.of(connection.getJarFileURL().toURI()).toAbsolutePath().normalize());
            }
        }
        // Le JAR mince du projet, voisin du JAR ombré, est MINOS lui-même et non une dépendance.
        jars.removeIf(jar -> shadedJar.getParent().equals(jar.getParent()));
        return jars;
    }
}
