package com.minos.runtime;

import com.minos.io.PrivateLocalStorage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Ownership-only Windows launcher. It does not impose AppContainer ACL/network restrictions. */
final class WindowsJobObjectProcessOwnership {

    private static final System.Logger LOGGER =
            System.getLogger(WindowsJobObjectProcessOwnership.class.getName());

    private static final String LAUNCHER_NAME = "windows-job-object-owner-v1.ps1";
    private static final String RESOURCE = "/com/minos/runtime/" + LAUNCHER_NAME;

    private final Path powershell;
    private final Path launcher;

    private WindowsJobObjectProcessOwnership(Path powershell, Path launcher) {
        this.powershell = powershell;
        this.launcher = launcher;
    }

    static Optional<WindowsJobObjectProcessOwnership> discover(Path minosHome) {
        Objects.requireNonNull(minosHome, "minosHome");
        if (WorkerSandboxQualification.currentPlatform() != WorkerSandboxQualification.Platform.WINDOWS) {
            return Optional.empty();
        }
        Optional<Path> powershell = CommandLocator.windowsPowerShell();
        if (powershell.isEmpty()) return Optional.empty();
        try {
            return Optional.of(new WindowsJobObjectProcessOwnership(
                    powershell.orElseThrow(), installLauncher(minosHome.toAbsolutePath().normalize())));
        } catch (IOException failure) {
            // Not just "PowerShell missing": this can also mean the launcher could not be installed
            // as owner-only (e.g. the private-storage filesystem could not enforce or verify
            // ownership). The caller reports this as an ordinary "capability unavailable" diagnostic,
            // so the real cause must still be observable here rather than disappearing silently.
            LOGGER.log(System.Logger.Level.WARNING,
                    "MINOS Windows Job Object process ownership is unavailable", failure);
            return Optional.empty();
        }
    }

    ProcessIndexerExecutor.ProcessPlanTransformer transformer() {
        return new ProcessIndexerExecutor.ProcessPlanTransformer() {
            @Override
            public IndexerProcessPlan transform(IndexerProcessPlan plan, Path runDirectory) throws Exception {
                Objects.requireNonNull(plan, "plan");
                Path planFile = runDirectory.resolve("windows-job-object-plan.txt").toAbsolutePath().normalize();
                List<String> command = resolveCommand(plan.command());
                Map<String, String> environment = ProviderProcessEnvironment.sanitize(
                        new ProcessBuilder().environment(), plan.environment());
                writePlan(planFile, command, environment, plan.workingDirectory());
                return new IndexerProcessPlan(
                        List.of(
                                powershell.toString(),
                                "-NoLogo",
                                "-NoProfile",
                                "-NonInteractive",
                                "-ExecutionPolicy",
                                "Bypass",
                                "-File",
                                launcher.toString(),
                                "-Plan",
                                planFile.toString()),
                        plan.workingDirectory(),
                        Map.of(),
                        plan.generatedArtifact(),
                        plan.timeout());
            }

            @Override
            public boolean trustedLauncherRequiresParentEnvironment() {
                return true;
            }
        };
    }

    private static List<String> resolveCommand(List<String> original) throws IOException {
        if (original == null || original.isEmpty()) throw new IOException("provider command is empty");
        List<String> command = new ArrayList<>(original);
        Path first = Path.of(command.getFirst());
        Path executable;
        if (first.isAbsolute()) {
            executable = first.toAbsolutePath().normalize();
            if (!Files.isRegularFile(executable)) {
                throw new IOException("provider executable is missing: " + executable);
            }
        } else {
            executable = CommandLocator.find(command.getFirst())
                    .orElseThrow(() -> new IOException("provider executable is not resolvable: " + command.getFirst()));
        }
        command.set(0, executable.toString());
        return List.copyOf(command);
    }

    private static Path installLauncher(Path minosHome) throws IOException {
        Path directory = minosHome.resolve("sandbox").toAbsolutePath().normalize();
        Files.createDirectories(directory);
        Path target = directory.resolve(LAUNCHER_NAME);
        // Assembled from its template and the shared Win32 fragments, then published as one
        // self-contained file: the script that executes still has a single hash and no include path.
        String launcher = WindowsContainmentScript.assemble(LAUNCHER_NAME);
        Path partial = PrivateLocalStorage.createPrivateTempFile(directory, ".windows-job-owner-", ".ps1");
        try {
            Files.writeString(partial, launcher, StandardCharsets.UTF_8,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(partial);
        }
        return target;
    }

    private static void writePlan(
            Path target,
            List<String> command,
            Map<String, String> environment,
            Path workingDirectory
    ) throws IOException {
        StringBuilder out = new StringBuilder();
        writeList(out, "command", command);
        List<Map.Entry<String, String>> env = environment.entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().toLowerCase(java.util.Locale.ROOT)))
                .toList();
        out.append("environment.count=").append(env.size()).append('\n');
        for (int index = 0; index < env.size(); index++) {
            Map.Entry<String, String> entry = env.get(index);
            out.append("environment.").append(index).append(".key=").append(encoded(entry.getKey())).append('\n');
            out.append("environment.").append(index).append(".value=").append(encoded(entry.getValue())).append('\n');
        }
        out.append("working=").append(encoded(workingDirectory.toAbsolutePath().normalize().toString())).append('\n');
        Files.writeString(target, out.toString(), StandardCharsets.UTF_8);
    }

    private static void writeList(StringBuilder out, String prefix, List<String> values) {
        out.append(prefix).append(".count=").append(values.size()).append('\n');
        for (int index = 0; index < values.size(); index++) {
            out.append(prefix).append('.').append(index).append('=').append(encoded(values.get(index))).append('\n');
        }
    }

    private static String encoded(String value) {
        if (value == null || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Windows Job Object plan value contains a forbidden NUL");
        }
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
