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
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
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
import java.util.Set;
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
    private static final List<Path> LINUX_SYSTEM_DIRECTORIES = List.of(
            Path.of("/usr/bin"), Path.of("/bin"), Path.of("/usr/sbin"), Path.of("/sbin"));
    private static final String USER_SCOPE = "--user";
    private static final String QUIET = "--quiet";
    private static final String WINDOWS_LAUNCHER_LABEL = "Windows Job Object launcher";
    private static final String WINDOWS_TEMP_LAUNCHER_LABEL = "Windows Job Object launcher temporary file";
    private static final Object WINDOWS_INSTALL_LOCK = new Object();
    private static final Object LINUX_PROBE_LOCK = new Object();
    private static volatile Boolean linuxCapability;
    private static volatile LinuxSystemdTools linuxSystemdTools;

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
        if (!isLinux()) return false;
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
        if (Files.exists(resolved, LinkOption.NOFOLLOW_LINKS) && !isPhysicalDirectory(resolved)) {
            throw new IOException(label + " must be a physical directory, not a symlink or reparse point: " + resolved);
        }
        Files.createDirectories(resolved);
        if (!isPhysicalDirectory(resolved)) {
            throw new IOException(label + " is not a private physical directory: " + resolved);
        }
        restrictWindowsOwnerOnly(resolved, label);
        if (!isPhysicalDirectory(resolved)) {
            throw new IOException(label + " changed while securing it: " + resolved);
        }
    }

    private static boolean isPhysicalDirectory(Path directory) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return attributes.isDirectory() && !attributes.isSymbolicLink() && !attributes.isOther();
    }

    private static void restrictWindowsOwnerOnly(Path entry, String label) throws IOException {
        Path file = Objects.requireNonNull(entry, "entry").toAbsolutePath().normalize();
        BasicFileAttributes attributes = Files.readAttributes(
                file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || attributes.isOther()) {
            throw new IOException(label + " must not be a symbolic link or reparse point: " + file);
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
        BasicFileAttributes securedAttributes = Files.readAttributes(
                file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!ownerOnlyApplied || securedAttributes.isSymbolicLink() || securedAttributes.isOther()) {
            throw new IOException(label + " ACL is not restricted to its owner: " + file);
        }
    }

    private static Launch startLinux(ProcessBuilder original) throws IOException {
        LinuxSystemdTools tools = requireLinuxCapability();
        String unit = "minos-intellij-" + UUID.randomUUID().toString().replace("-", "");
        List<String> command = new ArrayList<>();
        command.add(tools.systemdRun().toString());
        command.add(USER_SCOPE);
        command.add("--scope");
        command.add(QUIET);
        command.add("--unit=" + unit);
        command.add("--");
        command.addAll(original.command());
        Process process = wrapper(original, command).start();
        return new Launch(process, new LinuxScopeBoundary(process, unit + ".scope", tools.systemctl()));
    }

    private static LinuxSystemdTools requireLinuxCapability() throws IOException {
        Boolean cached = linuxCapability;
        LinuxSystemdTools cachedTools = linuxSystemdTools;
        if (Boolean.TRUE.equals(cached) && cachedTools != null) return cachedTools;
        if (Boolean.FALSE.equals(cached)) throw new IOException("systemd user scope ownership is unavailable");
        synchronized (LINUX_PROBE_LOCK) {
            if (linuxCapability == null) {
                try {
                    LinuxSystemdTools candidate = new LinuxSystemdTools(
                            linuxSystemExecutable("systemctl"),
                            linuxSystemExecutable("systemd-run"));
                    if (probeLinuxCapability(candidate)) {
                        linuxSystemdTools = candidate;
                        linuxCapability = true;
                    } else {
                        linuxCapability = false;
                    }
                } catch (IOException unavailable) {
                    linuxCapability = false;
                }
            }
            if (!Boolean.TRUE.equals(linuxCapability) || linuxSystemdTools == null) {
                throw new IOException(
                        "strong MINOS CLI ownership requires root-owned systemd tools, a working user manager and transient scopes");
            }
            return linuxSystemdTools;
        }
    }

    private static boolean probeLinuxCapability(LinuxSystemdTools tools) {
        String unit = "minos-intellij-probe-" + UUID.randomUUID().toString().replace("-", "");
        try {
            if (runControl(List.of(tools.systemctl().toString(), USER_SCOPE, "show-environment"), false) != 0) {
                return false;
            }
            return runControl(List.of(
                    tools.systemdRun().toString(), USER_SCOPE, "--scope", QUIET, "--unit=" + unit,
                    "--", "/bin/true"), false) == 0;
        } catch (IOException failure) {
            return false;
        }
    }

    /** Resolves a systemd security-authority executable without consulting PATH. */
    static Path linuxSystemExecutable(String executable) throws IOException {
        if (!isLinux()) throw new IOException("Linux system executable requested on a non-Linux host");
        if (executable == null || executable.isBlank()
                || executable.indexOf('/') >= 0 || executable.indexOf('\\') >= 0) {
            throw new IOException("Linux system executable must be a simple name");
        }
        for (Path configuredDirectory : LINUX_SYSTEM_DIRECTORIES) {
            try {
                Path directory = configuredDirectory.toRealPath();
                if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                        || !isRootOwnedAndNotGroupWorldWritable(directory)) {
                    continue;
                }
                Path candidate = directory.resolve(executable).normalize().toRealPath();
                if (!candidate.startsWith(directory)
                        || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
                        || !Files.isExecutable(candidate)
                        || !isRootOwnedAndNotGroupWorldWritable(candidate)) {
                    continue;
                }
                return candidate;
            } catch (IOException | SecurityException | UnsupportedOperationException unavailable) {
                // Continue through fixed system roots; never fall back to PATH.
            }
        }
        throw new IOException("trusted Linux system executable is unavailable: " + executable);
    }

    private static boolean isRootOwnedAndNotGroupWorldWritable(Path path) {
        try {
            Object uid = Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS);
            if (!(uid instanceof Number number) || number.longValue() != 0L) return false;
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
            return !permissions.contains(PosixFilePermission.GROUP_WRITE)
                    && !permissions.contains(PosixFilePermission.OTHERS_WRITE);
        } catch (IOException | SecurityException | UnsupportedOperationException unavailable) {
            return false;
        }
    }

    private static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
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
            try {
                writeWindowsLauncherResource(temporary);
                publishWindowsLauncher(temporary, target);
                return target;
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static void writeWindowsLauncherResource(Path temporary) throws IOException {
        restrictWindowsOwnerOnly(temporary, WINDOWS_TEMP_LAUNCHER_LABEL);
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
        restrictWindowsOwnerOnly(temporary, WINDOWS_TEMP_LAUNCHER_LABEL);
    }

    private static void publishWindowsLauncher(Path temporary, Path target) throws IOException {
        boolean published = false;
        try {
            moveWindowsLauncherAtomically(temporary, target);
            published = true;
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(target)) {
                throw new IOException(WINDOWS_LAUNCHER_LABEL + " is not a regular file: " + target);
            }
            restrictWindowsOwnerOnly(target, WINDOWS_LAUNCHER_LABEL);
        } catch (IOException failure) {
            if (published) deleteFailedWindowsLauncher(target, failure);
            throw failure;
        }
    }

    private static void moveWindowsLauncherAtomically(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            throw new IOException(WINDOWS_LAUNCHER_LABEL + " requires atomic publication", unsupported);
        }
    }

    private static void deleteFailedWindowsLauncher(Path target, IOException failure) {
        try {
            Files.deleteIfExists(target);
        } catch (IOException cleanup) {
            failure.addSuppressed(cleanup);
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

    static Path resolveWindowsExecutable(String executable) throws IOException {
        String value = Objects.requireNonNull(executable, "executable");
        if (value.equalsIgnoreCase("cmd") || value.equalsIgnoreCase("cmd.exe")) {
            return windowsSystemExecutable("cmd.exe");
        }
        Path candidate = Path.of(value);
        if (candidate.isAbsolute() && Files.isRegularFile(candidate)) return candidate.toRealPath();
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
        if (first.isEmpty()) {
            throw new IOException("resolved Windows executable is not a regular file: " + value);
        }
        Path resolved = Path.of(first).toRealPath();
        if (!Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("resolved Windows executable is not a regular file: " + value);
        }
        return resolved;
    }

    private static Path windowsSystemExecutable(String executable) throws IOException {
        Path system32 = windowsSystem32();
        Path resolved = system32.resolve(executable).toRealPath();
        if (!resolved.startsWith(system32) || !Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Windows system executable is unavailable: " + resolved);
        }
        return resolved;
    }

    private static Path windowsPowerShell() throws IOException {
        Path system32 = windowsSystem32();
        Path powershell = system32.resolve("WindowsPowerShell").resolve("v1.0").resolve("powershell.exe").toRealPath();
        if (!powershell.startsWith(system32) || !Files.isRegularFile(powershell, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Windows PowerShell is unavailable: " + powershell);
        }
        return powershell;
    }

    private static Path windowsSystem32() throws IOException {
        String root = System.getenv("SystemRoot");
        if (root == null || root.isBlank()) throw new IOException("SystemRoot is unavailable");
        Path windowsRoot = Path.of(root);
        if (!windowsRoot.isAbsolute()) throw new IOException("SystemRoot must be absolute");
        Path realRoot = windowsRoot.toRealPath();
        Path system32 = realRoot.resolve("System32").toRealPath();
        if (!system32.startsWith(realRoot) || !Files.isDirectory(system32, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Windows System32 directory is unavailable: " + system32);
        }
        return system32;
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

    private record LinuxSystemdTools(Path systemctl, Path systemdRun) {
        private LinuxSystemdTools {
            Objects.requireNonNull(systemctl, "systemctl");
            Objects.requireNonNull(systemdRun, "systemdRun");
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
        private final Path systemctl;
        private final AtomicBoolean terminated = new AtomicBoolean();

        private LinuxScopeBoundary(Process wrapper, String scope, Path systemctl) {
            this.wrapper = wrapper;
            this.scope = scope;
            this.systemctl = systemctl;
        }

        @Override
        public void terminate() throws IOException {
            if (!terminated.compareAndSet(false, true)) return;
            IOException failure = null;
            try {
                int stop = runControl(List.of(systemctl.toString(), USER_SCOPE, "stop", scope), true);
                if (stop != 0) {
                    int active = runControl(List.of(systemctl.toString(), USER_SCOPE, "is-active", QUIET, scope), true);
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
