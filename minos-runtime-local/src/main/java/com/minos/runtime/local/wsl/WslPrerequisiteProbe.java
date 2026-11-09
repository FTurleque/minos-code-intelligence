package com.minos.runtime.local.wsl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Read-only probe of the prerequisites of the WSL route of Java indexing, run on Windows before any launch.
 *
 * <p>Three steps, each stopping the probe on a {@link WslCheckState#MISSING} check (checks not reached are
 * reported {@link WslCheckState#UNKNOWN}):</p>
 * <ol>
 *   <li>{@code wsl.exe -l -v}, decoded as UTF-16LE, gives the distributions and their WSL version;</li>
 *   <li>inside the distribution, a constant shell script reads the state of systemd and of the user bus
 *       ({@code XDG_RUNTIME_DIR}), the controllers delegable to the user manager, {@code bwrap} and the Java
 *       runtime, before the MINOS jar is ever launched;</li>
 *   <li>the MINOS jar applies the Linux scip-java checks, through the {@link WslJarProbe} extension point.</li>
 * </ol>
 *
 * <p>{@code wsl.exe} is resolved in the Windows system directory only, never through {@code PATH}. No user input
 * is interpolated into a command: the only variable argument is the distribution name, validated against
 * {@link #DISTRIBUTION_NAME}. No action text contains an absolute path, and no failure cause is reported.
 * The probe never runs Maven nor a provider.</p>
 */
public final class WslPrerequisiteProbe {

    /** Shape of a WSL distribution name accepted by MINOS; nothing a shell or option parser would reinterpret. */
    public static final Pattern DISTRIBUTION_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    /**
     * Constant script run by {@code /bin/sh -c} in the distribution. It prints {@code key=value} lines and ends
     * with {@code end=ok}. It contains no double quote and no trailing backslash, which Windows argument quoting
     * would alter.
     */
    static final String DISTRIBUTION_SCRIPT = String.join(" ",
            "if [ -d /run/systemd/system ]; then echo systemd=yes; else echo systemd=no; fi;",
            "if [ -S ${XDG_RUNTIME_DIR:-/nonexistent}/bus ]; then echo userbus=yes; else echo userbus=no; fi;",
            "if [ -f /sys/fs/cgroup/cgroup.controllers ]; then echo cgroup2=yes; else echo cgroup2=no; fi;",
            "u=$(id -u); f=/sys/fs/cgroup/user.slice/user-$u.slice/user@$u.service/cgroup.controllers;",
            "if [ -r $f ]; then echo controllers=$(cat $f); else echo controllers=; fi;",
            "if command -v bwrap >/dev/null 2>&1; then echo bwrap=yes; else echo bwrap=no; fi;",
            "if command -v java >/dev/null 2>&1;",
            "then echo jvm=$(java -XshowSettings:properties -version 2>&1 | grep -m1 java.specification.version);",
            "else echo jvm=; fi;",
            "echo end=ok");

    static final Duration LIST_TIMEOUT = Duration.ofSeconds(30);
    static final Duration DISTRIBUTION_TIMEOUT = Duration.ofSeconds(90);

    private static final int REQUIRED_JAVA_FEATURE = 21;
    private static final List<String> DELEGATED_CONTROLLERS = List.of("memory", "pids", "cpu");
    private static final Set<String> JAR_CHECKS =
            Set.of(WslCheck.MAVEN, WslCheck.PROJECT_JDK, WslCheck.MAVEN_REPOSITORY);
    private static final String NOT_REACHED = "not checked: fix the previous missing prerequisite first";
    private static final String WSL_INSTALL =
            "install WSL2 with a Linux distribution (wsl --install), then rerun";
    private static final String RETRY = "could not be observed; check that WSL2 starts (wsl -l -v), then rerun";
    private static final String DISTRIBUTION_UNREADABLE =
            "the distribution did not answer the probe; check that it starts, then rerun";

    private static final System.Logger LOGGER = System.getLogger(WslPrerequisiteProbe.class.getName());

    private final Path wslExecutable;
    private final WslCommandRunner runner;
    private final WslJarProbe jarProbe;

    /**
     * @param wslExecutable absolute {@code wsl.exe} from {@link #systemWslExecutable(Map)}, or {@code null} when
     *                      it could not be resolved (then the probe reports WSL2 missing)
     * @param runner        runs the {@code wsl.exe} commands
     * @param jarProbe      third step; {@link WslJarProbe#NOT_AVAILABLE} reports the jar checks unknown
     */
    public WslPrerequisiteProbe(Path wslExecutable, WslCommandRunner runner, WslJarProbe jarProbe) {
        this.wslExecutable = wslExecutable;
        this.runner = Objects.requireNonNull(runner, "runner");
        this.jarProbe = Objects.requireNonNull(jarProbe, "jarProbe");
    }

    /**
     * Resolves {@code wsl.exe} in {@code %SystemRoot%\System32} only. {@code PATH} is never a trust source; a
     * relative, blank or missing {@code SystemRoot}, or a candidate escaping it, gives an empty result.
     */
    public static Optional<Path> systemWslExecutable(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        Optional<String> systemRoot = WslCommandEnvironment.lookup(environment, "SystemRoot");
        if (systemRoot.isEmpty() || systemRoot.get().isBlank()) {
            return Optional.empty();
        }
        try {
            Path root = Path.of(systemRoot.get());
            if (!root.isAbsolute()) {
                return Optional.empty();
            }
            Path realRoot = root.toRealPath();
            Path candidate = realRoot.resolve("System32").resolve("wsl.exe").toRealPath();
            if (!candidate.startsWith(realRoot) || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            return Optional.of(candidate);
        } catch (InvalidPathException | IOException | SecurityException unavailable) {
            LOGGER.log(System.Logger.Level.DEBUG, "wsl.exe not found in the system directory ({0})",
                    unavailable.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** Operator trace of a check that could not be observed: exception class only, never a path or cause. */
    private static void logUnobserved(String check, Exception failure) {
        LOGGER.log(System.Logger.Level.WARNING, "WSL prerequisite {0} could not be observed ({1})",
                check, failure.getClass().getSimpleName());
    }

    /**
     * Runs the probe.
     *
     * @param requestedDistribution distribution name, or {@code null}/blank for the WSL default distribution
     * @throws IllegalArgumentException when the requested name does not match {@link #DISTRIBUTION_NAME}; no
     *                                  command is run then
     */
    public WslProbeReport probe(String requestedDistribution) {
        String requested = requestedDistribution == null || requestedDistribution.isBlank()
                ? null : requireValidName(requestedDistribution);
        Map<String, WslCheck> checks = new LinkedHashMap<>();
        Resolution resolution = resolveDistribution(requested);
        checks.put(WslCheck.WSL_VERSION, resolution.check());
        if (resolution.check().state() == WslCheckState.OK) {
            checks.putAll(probeDistribution(resolution.distribution()));
            if (checks.values().stream().noneMatch(check -> check.state() == WslCheckState.MISSING)) {
                checks.putAll(probeJar(resolution.distribution()));
            }
        }
        List<WslCheck> ordered = new ArrayList<>();
        for (String id : WslCheck.IDENTIFIERS) {
            ordered.add(checks.getOrDefault(id, WslCheck.unknown(id, NOT_REACHED)));
        }
        return new WslProbeReport(resolution.distribution(), ordered);
    }

    private static String requireValidName(String name) {
        if (!DISTRIBUTION_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "invalid WSL distribution name: use letters, digits, '.', '_' or '-' (at most 64 characters)");
        }
        return name;
    }

    private record Resolution(String distribution, WslCheck check) {
    }

    private record Distribution(String name, boolean isDefault, int version) {
    }

    private Resolution resolveDistribution(String requested) {
        if (wslExecutable == null) {
            return new Resolution(null, WslCheck.missing(WslCheck.WSL_VERSION, WSL_INSTALL));
        }
        WslCommandResult result;
        try {
            result = runner.run(List.of(wslExecutable.toString(), "-l", "-v"), LIST_TIMEOUT);
        } catch (IOException failure) {
            logUnobserved(WslCheck.WSL_VERSION, failure);
            return new Resolution(requested, WslCheck.unknown(WslCheck.WSL_VERSION, "WSL2 " + RETRY));
        }
        List<Distribution> distributions = result.exitCode() == 0 ? parseListing(result.stdout()) : List.of();
        if (distributions.isEmpty()) {
            return new Resolution(requested, WslCheck.missing(WslCheck.WSL_VERSION, WSL_INSTALL));
        }
        return select(distributions, requested);
    }

    /** Picks the requested (or default) distribution among those listed and checks its WSL version. */
    private static Resolution select(List<Distribution> distributions, String requested) {
        Optional<Distribution> target = requested == null
                ? distributions.stream().filter(Distribution::isDefault).findFirst()
                : distributions.stream().filter(d -> d.name().equalsIgnoreCase(requested)).findFirst();
        if (target.isEmpty()) {
            String action = requested == null
                    ? "set a default WSL distribution (wsl --set-default <name>) or name one explicitly"
                    : "install the WSL distribution " + requested + " or choose one listed by wsl -l -v";
            return new Resolution(requested, WslCheck.missing(WslCheck.WSL_VERSION, action));
        }
        Distribution distribution = target.get();
        if (distribution.version() != 2) {
            return new Resolution(distribution.name(), WslCheck.missing(WslCheck.WSL_VERSION,
                    "convert the distribution to WSL version 2 (wsl --set-version " + distribution.name() + " 2)"));
        }
        return new Resolution(distribution.name(), WslCheck.ok(WslCheck.WSL_VERSION));
    }

    /** Parses {@code wsl -l -v}: header (localized) skipped, name first, version last, {@code *} for default. */
    static List<Distribution> parseListing(byte[] utf16) {
        String text = new String(utf16, StandardCharsets.UTF_16LE).replace("\uFEFF", "").replace("\u0000", "");
        List<Distribution> distributions = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String trimmed = line.strip();
            boolean isDefault = trimmed.startsWith("*");
            if (isDefault) {
                trimmed = trimmed.substring(1).strip();
            }
            String[] tokens = trimmed.split("\\s+");
            if (tokens.length < 3 || !DISTRIBUTION_NAME.matcher(tokens[0]).matches()) {
                continue;
            }
            String version = tokens[tokens.length - 1];
            if (version.equals("1") || version.equals("2")) {
                distributions.add(new Distribution(tokens[0], isDefault, Integer.parseInt(version)));
            }
        }
        return distributions;
    }

    private Map<String, WslCheck> probeDistribution(String distribution) {
        Map<String, String> facts;
        try {
            WslCommandResult result = runner.run(List.of(wslExecutable.toString(), "-d", distribution, "--exec",
                    "/bin/sh", "-c", DISTRIBUTION_SCRIPT), DISTRIBUTION_TIMEOUT);
            facts = result.exitCode() == 0 ? parseFacts(result.stdout()) : Map.of();
        } catch (IOException failure) {
            logUnobserved(WslCheck.SYSTEMD, failure);
            facts = Map.of();
        }
        Map<String, WslCheck> checks = new LinkedHashMap<>();
        if (!"ok".equals(facts.get("end"))) {
            for (String id : List.of(WslCheck.SYSTEMD, WslCheck.CGROUP_DELEGATION, WslCheck.BUBBLEWRAP,
                    WslCheck.MINOS_JVM)) {
                checks.put(id, WslCheck.unknown(id, DISTRIBUTION_UNREADABLE));
            }
            return checks;
        }
        checks.put(WslCheck.SYSTEMD, systemd(facts));
        checks.put(WslCheck.CGROUP_DELEGATION, delegation(facts));
        checks.put(WslCheck.BUBBLEWRAP, "yes".equals(facts.get("bwrap"))
                ? WslCheck.ok(WslCheck.BUBBLEWRAP)
                : WslCheck.missing(WslCheck.BUBBLEWRAP, "install bubblewrap in the distribution"));
        checks.put(WslCheck.MINOS_JVM, jvm(facts.getOrDefault("jvm", "")));
        return checks;
    }

    private static Map<String, String> parseFacts(byte[] stdout) {
        Map<String, String> facts = new HashMap<>();
        for (String line : new String(stdout, StandardCharsets.UTF_8).split("\\R")) {
            int separator = line.indexOf('=');
            if (separator > 0) {
                facts.putIfAbsent(line.substring(0, separator).strip(), line.substring(separator + 1).strip());
            }
        }
        return facts;
    }

    private static WslCheck systemd(Map<String, String> facts) {
        if (!"yes".equals(facts.get("systemd"))) {
            return WslCheck.missing(WslCheck.SYSTEMD,
                    "enable systemd in the [boot] section of the distribution's wsl.conf, then run wsl --shutdown");
        }
        if (!"yes".equals(facts.get("userbus"))) {
            return WslCheck.missing(WslCheck.SYSTEMD,
                    "start the systemd user manager of your account so that XDG_RUNTIME_DIR and its user bus exist");
        }
        return WslCheck.ok(WslCheck.SYSTEMD);
    }

    private static WslCheck delegation(Map<String, String> facts) {
        if (!"yes".equals(facts.get("cgroup2"))) {
            return WslCheck.missing(WslCheck.CGROUP_DELEGATION,
                    "use the unified cgroup v2 hierarchy in WSL2 (cgroup v1 is not supported)");
        }
        Set<String> available = Set.copyOf(Arrays.asList(facts.getOrDefault("controllers", "").split("\\s+")));
        List<String> missing = DELEGATED_CONTROLLERS.stream().filter(c -> !available.contains(c)).toList();
        if (!missing.isEmpty()) {
            return WslCheck.missing(WslCheck.CGROUP_DELEGATION,
                    "delegate the cgroup controllers " + String.join(", ", missing)
                            + " to the systemd user manager (Delegate= of user@.service)");
        }
        return WslCheck.ok(WslCheck.CGROUP_DELEGATION);
    }

    private static WslCheck jvm(String value) {
        String action = "install a Java " + REQUIRED_JAVA_FEATURE + " runtime (or newer) in the distribution";
        int separator = value.lastIndexOf('=');
        String version = (separator >= 0 ? value.substring(separator + 1) : value).strip();
        if (version.isEmpty()) {
            return WslCheck.missing(WslCheck.MINOS_JVM, action);
        }
        String feature = version.startsWith("1.") ? version.substring(2) : version;
        int end = 0;
        while (end < feature.length() && Character.isDigit(feature.charAt(end))) {
            end++;
        }
        if (end == 0 || end > 4) {
            return WslCheck.unknown(WslCheck.MINOS_JVM, "the Java version of the distribution could not be read; "
                    + action);
        }
        return Integer.parseInt(feature.substring(0, end)) >= REQUIRED_JAVA_FEATURE
                ? WslCheck.ok(WslCheck.MINOS_JVM)
                : WslCheck.missing(WslCheck.MINOS_JVM, action);
    }

    private Map<String, WslCheck> probeJar(String distribution) {
        Map<String, WslCheck> checks = new LinkedHashMap<>();
        List<WslCheck> reported;
        try {
            reported = jarProbe.probe(distribution);
        } catch (RuntimeException failure) {
            // A failing extension must not make the probe throw: its checks are reported unknown, never OK.
            logUnobserved(WslCheck.MAVEN, failure);
            reported = List.of();
        }
        for (WslCheck check : reported == null ? List.<WslCheck>of() : reported) {
            if (check != null && JAR_CHECKS.contains(check.id())) {
                checks.putIfAbsent(check.id(), check);
            }
        }
        for (String id : List.of(WslCheck.MAVEN, WslCheck.PROJECT_JDK, WslCheck.MAVEN_REPOSITORY)) {
            checks.putIfAbsent(id, WslCheck.unknown(id,
                    "not checked: the MINOS jar did not report this check from the distribution; rerun minos doctor"));
        }
        return checks;
    }
}
