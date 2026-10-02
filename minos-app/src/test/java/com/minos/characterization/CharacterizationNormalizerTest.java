package com.minos.characterization;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * A2 — les golden ne dépendent pas des outils installés sur l'hôte. Les entrées « Linux » reprennent
 * les lignes réelles du runner ubuntu de la CI (rustup installé) ; les entrées « Windows » celles du
 * poste qui a généré les golden.
 */
class CharacterizationNormalizerTest {

    @Test
    void doctorProviderDetailBlockIsOneHostLineWhateverTheInstalledTools() {
        String windows = """
                rust-analyzer-scip 0.3.2989 — BLOCKED [optional]
                  diagnostic: missing Rust runtime requirements: cargo, rustc, rust-analyzer
                scip-clang 0.4.0 — BLOCKED [optional]
                  diagnostic: scip-clang 0.4.0 upstream publishes no Windows binary
                scip-java 0.13.1 — NOT_INSTALLED [required]
                  diagnostic: Coursier is not installed in MINOS_HOME/tools and was not found in PATH
                  diagnostic: managed Maven 3.9.16 is not installed in MINOS_HOME/tools
                privateStorage[MINOS_HOME]: ENFORCED
                """;
        String linux = """
                rust-analyzer-scip 0.3.2989 — INVALID [optional]
                  executable: /home/runner/.cargo/bin/rustup
                  diagnostic: rust-analyzer component is not installed
                scip-clang 0.4.0 — READY [optional]
                  executable: /usr/local/bin/scip-clang
                scip-java 0.13.1 — NOT_INSTALLED [required]
                  diagnostic: Coursier is not installed in MINOS_HOME/tools and was not found in PATH
                privateStorage[MINOS_HOME]: ENFORCED
                """;
        String expected = """
                rust-analyzer-scip 0.3.2989 — <host> [optional]
                  diagnostic: <host>
                scip-clang 0.4.0 — <host> [optional]
                  diagnostic: <host>
                scip-java 0.13.1 — <host> [required]
                  diagnostic: <host>
                privateStorage[MINOS_HOME]: <host>
                """;

        assertEquals(expected, CharacterizationNormalizer.normalizeHostFacts(windows));
        assertEquals(expected, CharacterizationNormalizer.normalizeHostFacts(linux));
    }

    /**
     * Runner windows-2022 : le répertoire temporaire est fourni sous son nom court 8.3 alors que la
     * sortie porte le chemin long résolu (toRealPath), en JSON échappé. Ligne réelle de la CI.
     */
    @Test
    void theShortAndLongSpellingsOfTheTemporaryDirectoryAreBothTemp() {
        java.nio.file.Path shortName = java.nio.file.Path.of(
                "C:\\Users\\RUNNER~1\\AppData\\Local\\Temp\\junit-12586810752964563181");
        java.nio.file.Path longName = java.nio.file.Path.of(
                "C:\\Users\\runneradmin\\AppData\\Local\\Temp\\junit-12586810752964563181");
        java.nio.file.Path repository = java.nio.file.Path.of("D:\\a\\minos-code-intelligence\\minos-code-intelligence");
        CharacterizationNormalizer normalizer = new CharacterizationNormalizer(
                java.util.List.of(shortName, longName), java.util.List.of(repository));
        String ciLine = "{\"id\":\"x\",\"name\":\"a2-java\",\"rootPath\":"
                + "\"C:\\\\Users\\\\runneradmin\\\\AppData\\\\Local\\\\Temp\\\\junit-12586810752964563181\\\\java-project\"}";

        assertEquals("{\"id\":\"x\",\"name\":\"a2-java\",\"rootPath\":\"<TEMP>/java-project\"}", normalizer.normalize(ciLine));
        assertEquals("root: <TEMP>/git-project", normalizer.normalize(
                "root: C:\\Users\\RUNNER~1\\AppData\\Local\\Temp\\junit-12586810752964563181\\git-project"));
        assertEquals("<REPO>/fixtures/typescript", normalizer.normalize(
                "D:/a/minos-code-intelligence/minos-code-intelligence/fixtures/typescript"));
        // Un autre répertoire n'est jamais pris pour le répertoire temporaire.
        assertEquals("C:/Users/runneradmin/AppData/Local/Temp/junit-999/other",
                normalizer.normalize("C:/Users/runneradmin/AppData/Local/Temp/junit-999/other"));
    }

    /**
     * Même cas sur un vrai volume Windows : un répertoire fourni sous son nom court 8.3 est reconnu
     * sous son nom long réel (toRealPath), sans graphie injectée. Ignoré hors Windows ou quand le
     * volume ne génère pas de noms courts.
     */
    @Test
    void theDefaultConstructorRecognisesTheRealLongNameOfAShortTemporaryDirectory(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                System.getProperty("os.name", "").startsWith("Windows"), "8.3 short names are a Windows feature");
        java.nio.file.Path longName = java.nio.file.Files.createDirectories(
                temp.resolve("a2 characterization long directory name")).toRealPath();
        Process process = new ProcessBuilder("cmd", "/c", "for %I in (\"" + longName + "\") do @echo %~sI")
                .redirectErrorStream(true).start();
        String shortName = new String(process.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8).strip();
        process.waitFor();
        org.junit.jupiter.api.Assumptions.assumeTrue(
                !shortName.isEmpty() && !shortName.equals(longName.toString()), "no 8.3 short name on this volume");

        CharacterizationNormalizer normalizer = new CharacterizationNormalizer(
                java.nio.file.Path.of(shortName), java.nio.file.Path.of("unused-repository-root"));

        assertEquals("\"rootPath\":\"<TEMP>/java-project\"", normalizer.normalize(
                "\"rootPath\":\"" + longName.resolve("java-project").toString().replace("\\", "\\\\") + "\""));
    }

    @Test
    void providersTableMasksOnlyTheRuntimeStateColumn() {
        String windows = "rust-analyzer-scip\t0.3.2989\tQUALIFIED_WITH_CONSTRAINTS\tBLOCKED\tscore=45\n"
                + "scip-java\t0.13.1\tQUALIFIED_WITH_CONSTRAINTS\tNOT_INSTALLED\tscore=69\n";
        String linux = "rust-analyzer-scip\t0.3.2989\tQUALIFIED_WITH_CONSTRAINTS\tINVALID\tscore=45\n"
                + "scip-java\t0.13.1\tQUALIFIED_WITH_CONSTRAINTS\tREADY\tscore=69\n";
        String expected = "rust-analyzer-scip\t0.3.2989\tQUALIFIED_WITH_CONSTRAINTS\t<host>\tscore=45\n"
                + "scip-java\t0.13.1\tQUALIFIED_WITH_CONSTRAINTS\t<host>\tscore=69\n";

        assertEquals(expected, CharacterizationNormalizer.normalizeProviderRuntimeHostFacts(windows));
        assertEquals(expected, CharacterizationNormalizer.normalizeProviderRuntimeHostFacts(linux));
        // Nom, version, qualification et score restent comparés.
        assertNotEquals(expected, CharacterizationNormalizer.normalizeProviderRuntimeHostFacts(
                windows.replace("score=45", "score=46")));
        assertNotEquals(expected, CharacterizationNormalizer.normalizeProviderRuntimeHostFacts(
                windows.replace("QUALIFIED_WITH_CONSTRAINTS\tBLOCKED", "EXPERIMENTAL\tBLOCKED")));
        assertNotEquals(expected, CharacterizationNormalizer.normalizeProviderRuntimeHostFacts(
                windows.replace("0.3.2989", "0.3.2990")));
    }
}
