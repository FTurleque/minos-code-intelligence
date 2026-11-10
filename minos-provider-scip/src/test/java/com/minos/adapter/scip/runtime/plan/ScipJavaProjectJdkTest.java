package com.minos.adapter.scip.runtime.plan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The project JDK of scip-java: {@code minos.scip.java.project-jdk}, otherwise {@code JAVA_HOME} as before. */
class ScipJavaProjectJdkTest {

    private static final String JAVAC = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")
            ? "javac.exe" : "javac";

    @TempDir Path temp;

    @Test
    void aDesignatedJdkWinsOverJavaHome() throws IOException {
        Path designated = jdk("designated");
        Path javaHome = jdk("java-home");
        ScipJavaProjectJdk jdk = ScipJavaProjectJdk.of(designated.toString(), env(javaHome.toString()));

        assertTrue(jdk.designated());
        assertEquals(Optional.empty(), jdk.problem());
        assertEquals(designated.toAbsolutePath().normalize(), jdk.home());
        assertTrue(jdk.java().startsWith(designated));
    }

    @Test
    void withoutTheSettingTheJdkOfJavaHomeIsUsedUnchanged() throws IOException {
        Path javaHome = jdk("java-home");
        ScipJavaProjectJdk jdk = ScipJavaProjectJdk.of("  ", env(javaHome.toString()));

        assertFalse(jdk.designated());
        assertEquals(Optional.empty(), jdk.problem());
        assertEquals(javaHome, jdk.home());
    }

    @Test
    void neitherTheSettingNorJavaHomeIsRefusedByNamingTheSettingWithoutAnyPath() {
        ScipJavaProjectJdk jdk = ScipJavaProjectJdk.of(null, env(null));

        String problem = jdk.problem().orElseThrow();
        IllegalStateException refusal = assertThrows(IllegalStateException.class, jdk::home);

        assertEquals("JAVA_HOME is not set to the project JDK and minos.scip.java.project-jdk is not set", problem);
        assertEquals("scip-java requires a project JDK: JAVA_HOME is not set and minos.scip.java.project-jdk is not set",
                refusal.getMessage());
    }

    @Test
    void aDesignatedDirectoryWithoutJavacIsRefusedByTheSettingAndNeverByItsPath() throws IOException {
        Path notAJdk = Files.createDirectories(temp.resolve("not-a-jdk"));
        for (String designation : new String[]{notAJdk.toString(), "relative/jdk", "\0bad"}) {
            ScipJavaProjectJdk jdk = ScipJavaProjectJdk.of(designation, env(null));

            String problem = jdk.problem().orElseThrow();
            IllegalStateException refusal = assertThrows(IllegalStateException.class, jdk::home);

            assertEquals("the project JDK designated by minos.scip.java.project-jdk does not contain javac", problem);
            assertFalse(refusal.getMessage().contains(notAJdk.toString()), refusal.getMessage());
            assertTrue(refusal.getMessage().contains(ScipJavaProjectJdk.SETTING), refusal.getMessage());
        }
    }

    @Test
    void theDiagnosticOfAJavaHomeWithoutJavacNamesTheVariableWithoutItsPath() throws IOException {
        Path notAJdk = Files.createDirectories(temp.resolve("no-jdk"));
        ScipJavaProjectJdk jdk = ScipJavaProjectJdk.of(null, env(notAJdk.toString()));

        String problem = jdk.problem().orElseThrow();

        assertEquals("JAVA_HOME does not contain javac", problem);
        assertFalse(problem.contains(notAJdk.toString()), problem);
    }

    @Test
    void theRefusalOfAJavaHomeWithoutJavacNamesTheVariableWithoutItsPath() throws IOException {
        Path notAJdk = Files.createDirectories(temp.resolve("no-jdk"));
        ScipJavaProjectJdk jdk = ScipJavaProjectJdk.of(null, env(notAJdk.toString()));

        String refusal = assertThrows(IllegalStateException.class, jdk::home).getMessage();

        assertEquals("JAVA_HOME does not point to a JDK containing javac", refusal);
        assertFalse(refusal.contains(notAJdk.toString()), refusal);
    }

    @Test
    void theProductionSourcesReadTheProcessEnvironment() {
        assertFalse(ScipJavaProjectJdk.fromEnvironment().designated());
        assertTrue(ScipJavaProjectJdk.configured(temp.toString()).designated());
        assertEquals("minos.scip.java.project-jdk", ScipJavaProjectJdk.SETTING);
        assertEquals("MINOS_SCIP_JAVA_PROJECT_JDK", ScipJavaProjectJdk.ENVIRONMENT);
    }

    private Path jdk(String name) throws IOException {
        Path home = temp.resolve(name);
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(home.resolve("bin").resolve(JAVAC), "");
        return home;
    }

    private static java.util.function.UnaryOperator<String> env(String javaHome) {
        return javaHome == null ? name -> null : name -> Map.of("JAVA_HOME", javaHome).get(name);
    }
}
