package com.minos.adapter.scip.runtime.install;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs {@link ManagedRuntimeScenarios} in a child JVM whose {@code PATH} holds nothing but the directory of
 * fake tools the test wrote: the managers resolve {@code node}, {@code npm}, {@code python}, {@code go}... from
 * the process {@code PATH}, which a test cannot change in its own JVM and must not read from the host. The
 * child environment is built from scratch (nothing is inherited) so the outcome never depends on the host.
 */
final class ChildJvm {

    /** What the child reported: its exit code, the {@code key=value} lines it printed and its stderr. */
    record Outcome(int exitCode, Map<String, String> values, String stderr) {
        String get(String key) {
            return values.get(key);
        }
    }

    private static final String MARKER = "@@";

    private ChildJvm() {
    }

    static Outcome run(Path toolsDirectory, Path scratch, Map<String, String> extraEnvironment, String... arguments)
            throws IOException, InterruptedException {
        boolean windows = FakeTools.windows();
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString());
        // Keep the coverage agent of the surefire JVM (when there is one) so the child's execution is measured too.
        for (String argument : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (argument.startsWith("-javaagent:")) command.add(argument);
        }
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(ManagedRuntimeScenarios.class.getName());
        command.addAll(List.of(arguments));

        Path stdout = scratch.resolve("child.stdout");
        Path stderr = scratch.resolve("child.stderr");
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(scratch.toFile());
        builder.redirectOutput(stdout.toFile());
        builder.redirectError(stderr.toFile());
        Map<String, String> environment = builder.environment();
        environment.clear();
        environment.put("PATH", toolsDirectory.toAbsolutePath().normalize().toString());
        environment.put("JAVA_HOME", System.getProperty("java.home"));
        if (windows) {
            // Process creation and cmd.exe resolution need the system root; it is not a tool search path.
            String systemRoot = System.getenv("SystemRoot");
            if (systemRoot != null) environment.put("SystemRoot", systemRoot);
        }
        environment.putAll(extraEnvironment);

        Process process = builder.start();
        if (!process.waitFor(3, TimeUnit.MINUTES)) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            throw new IllegalStateException("the scenario child JVM did not finish");
        }
        Map<String, String> values = new LinkedHashMap<>();
        // Lenient decoding: the child may print text in the platform charset, which must not fail the parent.
        for (String line : new String(Files.readAllBytes(stdout), StandardCharsets.UTF_8).split("\\R")) {
            if (!line.startsWith(MARKER)) continue;
            int separator = line.indexOf('=');
            if (separator > 0) values.put(line.substring(MARKER.length(), separator), line.substring(separator + 1));
        }
        return new Outcome(process.exitValue(), values, new String(Files.readAllBytes(stderr), StandardCharsets.UTF_8));
    }

    static String marker() {
        return MARKER;
    }
}
