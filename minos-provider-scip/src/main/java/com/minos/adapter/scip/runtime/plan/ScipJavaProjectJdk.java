package com.minos.adapter.scip.runtime.plan;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * The project JDK scip-java builds with: the JDK designated by {@link #SETTING} (JVM property, environment variable
 * {@link #ENVIRONMENT}, then MINOS configuration file key, read like {@link ScipJavaMavenOffline#REPOSITORY_SETTING}),
 * otherwise the JDK of {@code JAVA_HOME}.
 *
 * <p>Either source must name a JDK that contains {@code javac}. A JDK designated by the setting is never named by its
 * path in a message: the message names the setting.</p>
 */
public final class ScipJavaProjectJdk {

    /** The setting that designates the project JDK (JVM property and configuration file key). */
    public static final String SETTING = "minos.scip.java.project-jdk";
    /** The environment variable of the same setting. */
    public static final String ENVIRONMENT = "MINOS_SCIP_JAVA_PROJECT_JDK";
    static final String JAVA_HOME = "JAVA_HOME";

    private final String designation;
    private final Function<String, String> environment;

    private ScipJavaProjectJdk(String designation, Function<String, String> environment) {
        this.designation = designation == null || designation.isBlank() ? null : designation.trim();
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    /** The project JDK of this installation: {@code designation} is the value of {@link #SETTING}, null when unset. */
    public static ScipJavaProjectJdk configured(String designation) {
        return new ScipJavaProjectJdk(designation, System::getenv);
    }

    /** The project JDK of {@code JAVA_HOME} alone, for a runtime composed without MINOS settings. */
    public static ScipJavaProjectJdk fromEnvironment() {
        return configured(null);
    }

    /** Test seam: the environment that {@code JAVA_HOME} is read from is supplied. */
    static ScipJavaProjectJdk of(String designation, Function<String, String> environment) {
        return new ScipJavaProjectJdk(designation, environment);
    }

    /** Whether the JDK comes from {@link #SETTING} rather than from {@code JAVA_HOME}. */
    boolean designated() {
        return designation != null;
    }

    /**
     * Why the project JDK is unusable, as a diagnostic of the runtime; empty when it is usable. A designated JDK is
     * named by the setting only.
     */
    public Optional<String> problem() {
        if (designation != null) {
            return containsJavac(designation) ? Optional.empty()
                    : Optional.of("the project JDK designated by " + SETTING + " does not contain javac");
        }
        String javaHome = environment.apply(JAVA_HOME);
        if (javaHome == null || javaHome.isBlank()) {
            return Optional.of("JAVA_HOME is not set to the project JDK and " + SETTING + " is not set");
        }
        return containsJavac(javaHome) ? Optional.empty()
                : Optional.of("JAVA_HOME does not contain javac: " + javaHome);
    }

    /**
     * The home of the project JDK, for a plan.
     *
     * @throws IllegalStateException when the JDK is not designated or contains no {@code javac}
     */
    public Path home() {
        if (designation != null) {
            if (!containsJavac(designation)) {
                throw new IllegalStateException(
                        "scip-java requires a project JDK: the JDK designated by " + SETTING + " does not contain javac");
            }
            return Path.of(designation).toAbsolutePath().normalize();
        }
        String javaHome = environment.apply(JAVA_HOME);
        if (javaHome == null || javaHome.isBlank()) {
            throw new IllegalStateException(
                    "scip-java requires a project JDK: JAVA_HOME is not set and " + SETTING + " is not set");
        }
        if (!containsJavac(javaHome)) {
            throw new IllegalStateException("JAVA_HOME does not point to a JDK containing javac: " + javaHome);
        }
        return Path.of(javaHome);
    }

    /** The {@code java} launcher of the project JDK ({@code java.exe} when the JDK is a Windows one). */
    public Path java() {
        Path bin = home().resolve("bin");
        return Files.isRegularFile(bin.resolve("java.exe")) ? bin.resolve("java.exe") : bin.resolve("java");
    }

    private static boolean containsJavac(String home) {
        try {
            Path root = Path.of(home);
            Path bin = root.resolve("bin");
            return root.isAbsolute()
                    && (Files.isRegularFile(bin.resolve("javac")) || Files.isRegularFile(bin.resolve("javac.exe")));
        } catch (InvalidPathException invalid) {
            return false;
        }
    }
}
