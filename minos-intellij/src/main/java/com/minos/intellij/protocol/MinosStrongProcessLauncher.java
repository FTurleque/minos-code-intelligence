package com.minos.intellij.protocol;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Starts the MINOS CLI behind an OS ownership boundary established before CLI code can spawn.
 * Windows uses a Job Object assigned while the CLI process is suspended. Linux uses a transient
 * systemd user scope (cgroup ownership) after a one-time capability probe. Unsupported or
 * unqualified hosts fail closed; ProcessHandle tracking remains defence in depth in the supervisor.
 */
final class MinosStrongProcessLauncher {

    private static final long CONTROL_TIMEOUT_SECONDS = 5L;
    private static final int MAX_CONTROL_OUTPUT_BYTES = 64 * 1024;
    private static final Duration STALE_PLAN_AGE = Duration.ofHours(24);
    private static final String SYSTEMCTL = "systemctl";
    private static final String USER_SCOPE = "--user";
    private static final String QUIET = "--quiet";
    private static final Object WINDOWS_INSTALL_LOCK = new Object();
    private static final Object LINUX_PROBE_LOCK = new Object();
    private static volatile Boolean linuxCapability;

    private MinosStrongProcessLauncher() {
    }

    static Launch start(ProcessBuilder original, String configuredMinosHome) throws IOException {
        Objects.requireNonNull(original, "original");
        if (original.command().isEmpty()) throw new IllegalArgumentException("MINOS command must not be empty");
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return startWindows(original, configuredMinosHome);
        if (os.contains("linux")) return startLinux(original);
        throw new IOException("strong MINOS CLI process ownership is unsupported on this platform: " + os);
    }

    static boolean linuxOwnershipAvailableForTests() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")) return false;
        try {
            requireLinuxCapability();
            return true;
        } catch (IOException unavailable) {
            return false;
        }
    }

    private static Launch startWindows(ProcessBuilder original, String configuredMinosHome) throws IOException {
        Path home = ownershipHome(configuredMinosHome).toAbsolutePath().normalize();
        ensureWindowsPrivateDirectory(home, "MINOS ownership home");
        Path intellijHome = home.resolve("intellij");
        ensureWindowsPrivateDirectory(intellijHome, "IntelliJ ownership directory");
        Path ownership = intellijHome.resolve("process-ownership");
        ensureWindowsPrivateDirectory(ownership, "IntelliJ process ownership directory");
        cleanupStaleWindowsPlans(ownership, Instant.now().minus(STALE_PLAN_AGE));
        Path launcher = installWindowsLauncher(ownership);
        Path plan = Files.createTempFile(ownership, "cli-", ".plan");
        boolean started = false;
        try {
            restrictWindowsPlan(plan);
            writeWindowsPlan(plan, original);
            List<String> wrapperCommand = List.of(
                    windowsPowerShell().toString(), "-NoLogo", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-File", launcher.toString(), "-Plan", plan.toString());
            Process process = wrapper(original, wrapperCommand).start();
            started = true;
            return new Launch(process, new WindowsJobBoundary(process, plan));
        } finally {
            if (!started) Files.deleteIfExists(plan);
        }
    }

    static void cleanupStaleWindowsPlans(Path ownership, Instant cutoff) throws IOException {
        Path root = Objects.requireNonNull(ownership, "ownership").toAbsolutePath().normalize();
        Objects.requireNonNull(cutoff, "cutoff");
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (var plans = Files.newDirectoryStream(root, "cli-*.plan")) {
            for (Path plan : plans) {
                if (Files.isSymbolicLink(plan) || !Files.isRegularFile(plan, LinkOption.NOFOLLOW_LINKS)) continue;
                if (Files.getLastModifiedTime(plan, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(cutoff)) {
                    Files.deleteIfExists(plan);
                }
            }
        }
    }

    static void restrictWindowsPlan(Path plan) throws IOException {
        restrictWindowsOwnerOnly(Objects.requireNonNull(plan, "plan"), "Windows ownership plan");
    }

    private static void ensureWindowsPrivateDirectory(Path directory, String label) throws IOException {
        Path resolved = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        if (Files.exists(resolved, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(resolved)) {
            throw new IOException(label + " must not be a symbolic link: " + resolved);
        }
        Files.createDirectories(resolved);
        if (!Files.isDirectory(resolved, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(resolved)) {
            throw new IOException(label + " is not a private directory: " + resolved);
        }
        restrictWindowsOwnerOnly(resolved, label);
        if (!Files.isDirectory(resolved, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(resolved)) {
            throw new IOException(label + " changed while securing it: " + resolved);
        }
    }

    private static void restrictWindowsOwnerOnly(Path entry, String label) throws IOException {
        Path file = Objects.requireNonNull(entry, "entry").toAbsolutePath().normalize();
        if (Files.isSymbolicLink(file)) {
            throw new IOException(label + " must not be a symbolic link: " + file);
        }
        AclFileAttributeView view = Files.getFileAttributeView(
                file, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null) {
            throw new IOException(label + " filesystem does not expose ACLs: " + file);
        }
        UserPrincipal owner = Files.getOwner(file, LinkOption.NOFOLLOW_LINKS);
        AclEntry ownerOnly = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                .build();
        view.setAcl(List.of(ownerOnly));
        List<AclEntry> applied = view.getAcl();
        boolean ownerOnlyApplied = !applied.isEmpty() && applied.stream()
                .allMatch(entryValue -> entryValue.type() == AclEntryType.ALLOW
                        && entryValue.principal().equals(owner));
        if (!ownerOnlyApplied || Files.isSymbolicLink(file)) {
            throw new IOException(label + " ACL is not restricted to its owner: " + file);
        }
    }

    private static Launch startLinux(ProcessBuilder original) throws IOException {
        requireLinuxCapability();
        String unit = "minos-intellij-" + UUID.randomUUID().toString().replace("-", "");
        List<String> command = new ArrayList<>();
        command.add("systemd-run");
        command.add(USER_SCOPE);
        command.add("--scope");
        command.add(QUIET);
        command.add("--unit=" + unit);
        command.add("--");
        command.addAll(original.command());
        Process process = wrapper(original, command).start();
        return new Launch(process, new LinuxScopeBoundary(process, unit + ".scope"));
    }

    private static void requireLinuxCapability() throws IOException {
        Boolean cached = linuxCapability;
        if (cached != null) {
            if (!cached) throw new IOException("systemd user scope ownership is unavailable");
            return;
        }
        synchronized (LINUX_PROBE_LOCK) {
            if (linuxCapability == null) linuxCapability = probeLinuxCapability();
            if (!linuxCapability) throw new IOException(
                    "strong MINOS CLI ownership requires a working systemd user manager and transient scopes");
        }
    }

    private static boolean probeLinuxCapability() {
        String unit = "minos-intellij-probe-" + UUID.randomUUID().toString().replace("-", "");
        try {
            if (runControl(List.of(SYSTEMCTL, USER_SCOPE, "show-environment"), false) != 0) return false;
            return runControl(List.of(
                    "systemd-run", USER_SCOPE, "--scope", QUIET, "--unit=" + unit,
                    "--", "/bin/true"), false) == 0;
        } catch (IOException failure) {
            return false;
        }
    }

    private static ProcessBuilder wrapper(ProcessBuilder original, List<String> command) {
        ProcessBuilder wrapper = new ProcessBuilder(command);
        wrapper.directory(original.directory());
        wrapper.environment().clear();
        wrapper.environment().putAll(original.environment());
        wrapper.redirectInput(original.redirectInput());
        wrapper.redirectOutput(original.redirectOutput());
        wrapper.redirectError(original.redirectError());
        wrapper.redirectErrorStream(original.redirectErrorStream());
        return wrapper;
    }

    private static Path ownershipHome(String configured) throws IOException {
        String raw = configured == null ? "" : configured.trim();
        boolean defaultHome = raw.isEmpty();
        if (defaultHome) raw = System.getProperty("user.home", "").trim();
        if (raw.isEmpty()) throw new IOException("cannot resolve a home for IntelliJ process ownership files");
        Path root = Path.of(raw).toAbsolutePath().normalize();
        return defaultHome ? root.resolve(".minos") : root;
    }

    private static Path installWindowsLauncher(Path ownership) throws IOException {
        synchronized (WINDOWS_INSTALL_LOCK) {
            Path target = ownership.resolve("windows-cli-job-owner-v1.ps1");
            Path temporary = Files.createTempFile(ownership, ".windows-cli-owner-", ".tmp");
            boolean published = false;
            try {
                restrictWindowsOwnerOnly(temporary, "Windows Job Object launcher temporary file");
                try (InputStream input = MinosStrongProcessLauncher.class.getResourceAsStream(
                        "/com/minos/intellij/protocol/windows-cli-job-owner-v1.ps1")) {
                    if (input == null) throw new IOException("packaged Windows Job Object launcher is missing");
                    try (OutputStream output = Files.newOutputStream(
                            temporary,
                            StandardOpenOption.WRITE,
                            StandardOpenOption.TRUNCATE_EXISTING,
                            LinkOption.NOFOLLOW_LINKS)) {
                        input.transferTo(output);
                    }
                }
                restrictWindowsOwnerOnly(temporary, "Windows Job Object launcher temporary file");
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    throw new IOException("Windows Job Object launcher requires atomic publication", unsupported);
                }
                published = true;
                if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(target)) {
                    throw new IOException("Windows Job Object launcher is not a regular file: " + target);
                }
                restrictWindowsOwnerOnly(target, "Windows Job Object launcher");
                return target;
            } catch (IOException failure) {
                if (published) {
                    try {
                        Files.deleteIfExists(target);
                    } catch (IOException cleanup) {
                        failure.addSuppressed(cleanup);
                    }
                }
                throw failure;
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static void writeWindowsPlan(Path plan, ProcessBuilder original) throws IOException {
        List<String> command = new ArrayList<>(original.command());
        command.set(0, resolveWindowsExecutable(command.getFirst()).toString());
        Path working = original.directory() == null
                ? Path.of(".").toAbsolutePath().normalize()
                : original.directory().toPath().toAbsolutePath().normalize();
        StringBuilder value = new StringBuilder();
        value.append("command.count=").append(command.size()).append('\n');
        for (int index = 0; index < command.size(); index++) {
            value.append("command.").append(index).append('=').append(encoded(command.get(index))).append('\n');
        }
        List<Map.Entry<String, String>> environment = original.environment().entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey, String.CASE_INSENSITIVE_ORDER))
                .toList();
        value.append("environment.count=").append(environment.size()).append('\n');
        for (int index = 0; index < environment.size(); index++) {
            Map.Entry<String, String> entry = environment.get(index);
            if (!validWindowsEnvironmentKey(entry.getKey())
                    || entry.getValue().indexOf('\0') >= 0) {
                throw new IOException("invalid environment entry for Windows ownership launcher: "
                        + entry.getKey());
            }
            value.append("environment.").append(index).append(".key=").append(encoded(entry.getKey())).append('\n');
            value.append("environment.").append(index).append(".value=").append(encoded(entry.getValue())).append('\n');
        }
        value.append("working=").append(encoded(working.toString())).append('\n');
        Files.writeString(plan, value, StandardCharsets.UTF_8,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS);
    }

    private static boolean validWindowsEnvironmentKey(String key) {
        if (key == null || key.isEmpty() || key.indexOf('\0') >= 0) return false;
        if (key.length() == 3 && key.charAt(0) == '='
                && Character.isLetter(key.charAt(1)) && key.charAt(2) == ':') {
            return true;
        }
        return key.indexOf('=') < 0;
    }

    private static Path resolveWindowsExecutable(String executable) throws IOException {
        String value = Objects.requireNonNull(executable, "executable");
        Path candidate = Path.of(value);
        if (candidate.isAbsolute() && Files.isRegularFile(candidate)) return candidate.toAbsolutePath().normalize();
        if (value.equalsIgnoreCase("cmd") || value.equalsIgnoreCase("cmd.exe")) {
            String comSpec = System.getenv("ComSpec");
            if (comSpec != null && !comSpec.isBlank() && Files.isRegularFile(Path.of(comSpec))) {
                return Path.of(comSpec).toAbsolutePath().normalize();
            }
        }
        Process process = new ProcessBuilder(windowsSystemExecutable("where.exe").toString(), value)
                .redirectErrorStream(true).start();
        byte[] output;
        try {
            if (!process.waitFor(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("timed out resolving Windows executable: " + value);
            }
            output = process.getInputStream().readNBytes(MAX_CONTROL_OUTPUT_BYTES + 1);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted resolving Windows executable: " + value, interrupted);
        }
        if (process.exitValue() != 0 || output.length > MAX_CONTROL_OUTPUT_BYTES) {
            throw new IOException("cannot resolve Windows executable for strong ownership: " + value);
        }
        String first = new String(output, StandardCharsets.UTF_8).lines().findFirst().orElse("").trim();
        if (first.isEmpty() || !Files.isRegularFile(Path.of(first))) {
            throw new IOException("resolved Windows executable is not a regular file: " + value);
        }
        return Path.of(first).toAbsolutePath().normalize();
    }

    private static Path windowsSystemExecutable(String executable) throws IOException {
        String root = System.getenv("SystemRoot");
        if (root == null || root.isBlank()) throw new IOException("SystemRoot is unavailable");
        Path resolved = Path.of(root, "System32", executable).toAbsolutePath().normalize();
        if (!Files.isRegularFile(resolved)) {
            throw new IOException("Windows system executable is unavailable: " + resolved);
        }
        return resolved;
    }

    private static Path windowsPowerShell() throws IOException {
        String root = System.getenv("SystemRoot");
        if (root == null || root.isBlank()) throw new IOException("SystemRoot is unavailable");
        Path powershell = Path.of(root, "System32", "WindowsPowerShell", "v1.0", "powershell.exe")
                .toAbsolutePath().normalize();
        if (!Files.isRegularFile(powershell)) throw new IOException("Windows PowerShell is unavailable: " + powershell);
        return powershell;
    }

    private static String encoded(String value) {
        return Base64.getEncoder().encodeToString(
                Objects.requireNonNull(value, "value").getBytes(StandardCharsets.UTF_8));
    }

    private static int runControl(List<String> command, boolean tolerateFailure) throws IOException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        byte[] output;
        try {
            if (!process.waitFor(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("ownership control command timed out: " + command.getFirst());
            }
            output = process.getInputStream().readNBytes(MAX_CONTROL_OUTPUT_BYTES + 1);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("ownership control command was interrupted", interrupted);
        }
        if (output.length > MAX_CONTROL_OUTPUT_BYTES) throw new IOException("ownership control output exceeds safety limit");
        int exit = process.exitValue();
        if (exit != 0 && !tolerateFailure) {
            String diagnostic = new String(output, StandardCharsets.UTF_8)
                    .replace('\r', ' ').replace('\n', ' ').trim();
            throw new IOException("ownership control command failed (exit=" + exit + "): " + diagnostic);
        }
        return exit;
    }

    record Launch(Process process, ProcessBoundary boundary) {
        Launch {
            Objects.requireNonNull(process, "process");
            Objects.requireNonNull(boundary, "boundary");
        }
    }

    interface ProcessBoundary {
        void terminate() throws IOException;

        static ProcessBoundary none() {
            return () -> { };
        }
    }

    private static final class WindowsJobBoundary implements ProcessBoundary {
        private final Process wrapper;
        private final Path plan;
        private final AtomicBoolean terminated = new AtomicBoolean();

        private WindowsJobBoundary(Process wrapper, Path plan) {
            this.wrapper = wrapper;
            this.plan = plan;
        }

        @Override
        public void terminate() throws IOException {
            if (!terminated.compareAndSet(false, true)) return;
            IOException failure = null;
            try {
                if (wrapper.isAlive()) wrapper.destroyForcibly();
                try {
                    if (!wrapper.waitFor(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS) && wrapper.isAlive()) {
                        failure = new IOException("Windows Job Object launcher did not terminate");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    failure = new IOException("interrupted terminating Windows Job Object launcher", interrupted);
                }
            } finally {
                try {
                    Files.deleteIfExists(plan);
                } catch (IOException cleanup) {
                    if (failure == null) failure = cleanup; else failure.addSuppressed(cleanup);
                }
            }
            if (failure != null) throw failure;
        }
    }

    private static final class LinuxScopeBoundary implements ProcessBoundary {
        private final Process wrapper;
        private final String scope;
        private final AtomicBoolean terminated = new AtomicBoolean();

        private LinuxScopeBoundary(Process wrapper, String scope) {
            this.wrapper = wrapper;
            this.scope = scope;
        }

        @Override
        public void terminate() throws IOException {
            if (!terminated.compareAndSet(false, true)) return;
            IOException failure = null;
            try {
                int stop = runControl(List.of(SYSTEMCTL, USER_SCOPE, "stop", scope), true);
                if (stop != 0) {
                    int active = runControl(List.of(SYSTEMCTL, USER_SCOPE, "is-active", QUIET, scope), true);
                    if (active == 0) failure = new IOException(
                            "systemd scope remained active after stop failure: " + scope);
                }
            } catch (IOException control) {
                failure = control;
            }
            if (wrapper.isAlive()) wrapper.destroyForcibly();
            try {
                wrapper.waitFor(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                if (failure == null) failure = new IOException(
                        "interrupted terminating systemd scope wrapper", interrupted);
                else failure.addSuppressed(interrupted);
            }
            if (wrapper.isAlive()) {
                IOException alive = new IOException("systemd scope wrapper remained alive: " + scope);
                if (failure == null) failure = alive; else failure.addSuppressed(alive);
            }
            if (failure != null) throw failure;
        }
    }
}
