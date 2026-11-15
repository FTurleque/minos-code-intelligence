package com.minos.runtime.local.process;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Builds the environment exposed to an external provider process.
 *
 * <p>Provider code is treated as untrusted. The parent MINOS process may carry database passwords,
 * hosted tokens, CI credentials or operator secrets, so provider processes must never inherit the
 * parent environment wholesale. Only the runtime/OS allowlists below are inherited implicitly;
 * provider-specific values must be declared explicitly by {@link IndexerProcessPlan#environment()}.
 * A variable whose name looks like a secret or a MINOS/CI credential is not forwarded, inherited or
 * declared: see {@link #isDeniedName(String)}.</p>
 */
public final class ProviderProcessEnvironment {

    private static final Set<String> COMMON_SAFE_INHERITED_KEYS = Set.of(
            "PATH",
            "JAVA_HOME",
            "JDK_HOME",
            "TEMP",
            "TMP",
            "TMPDIR",
            "LANG",
            "LC_ALL",
            "LC_CTYPE",
            "TZ",
            "DOTNET_ROOT",
            "DOTNET_CLI_TELEMETRY_OPTOUT",
            "DOTNET_NOLOGO",
            "NUGET_XMLDOC_MODE",
            "GOPATH",
            "GOMODCACHE",
            "GOCACHE",
            "CARGO_HOME",
            "RUSTUP_HOME",
            "COURSIER_CACHE"
    );

    /** Non-secret Windows process metadata required by PowerShell/AppContainer/toolchain startup. */
    private static final Set<String> WINDOWS_SAFE_INHERITED_KEYS = Set.of(
            "ALLUSERSPROFILE",
            "APPDATA",
            "CommonProgramFiles",
            "CommonProgramFiles(x86)",
            "CommonProgramW6432",
            "COMPUTERNAME",
            "ComSpec",
            "DriverData",
            "HOME",
            "HOMEDRIVE",
            "HOMEPATH",
            "LOCALAPPDATA",
            "LOGONSERVER",
            "NUMBER_OF_PROCESSORS",
            "OS",
            "PATHEXT",
            "POWERSHELL_DISTRIBUTION_CHANNEL",
            "PROCESSOR_ARCHITECTURE",
            "PROCESSOR_IDENTIFIER",
            "PROCESSOR_LEVEL",
            "PROCESSOR_REVISION",
            "ProgramData",
            "ProgramFiles",
            "ProgramFiles(x86)",
            "ProgramW6432",
            "PSModulePath",
            "PUBLIC",
            "SESSIONNAME",
            "SystemDrive",
            "SystemRoot",
            "USERDOMAIN",
            "USERDOMAIN_ROAMINGPROFILE",
            "USERNAME",
            "USERPROFILE",
            "WINDIR"
    );

    /**
     * What a trusted Windows launcher (the PowerShell program that contains a provider) needs to start,
     * and nothing else. Measured, not guessed: with only {@code PATH}, {@code PATHEXT}, {@code SystemRoot},
     * {@code TEMP} and {@code TMP} both the AppContainer and the Job Object launcher start and run a
     * provider. The other entries are the profile and machine locations a launcher resolves on a
     * configuration that differs from the one it was measured on (a roaming or domain profile): kept as a
     * margin, each of them a path or a name, none of them a secret. The provider itself never sees this
     * environment: it receives the one the sandbox plan carries.
     *
     * <p>{@code PSModuleAnalysisCachePath} is there because it was measured, on a machine whose image
     * ships many PowerShell modules (a CI runner): without it the launcher's PowerShell rebuilds its module
     * analysis cache on every start and spends about 23 seconds of CPU before it compiles anything, with it
     * (or with the whole environment) the same start takes a third of a second. It names a file, nothing
     * more.</p>
     */
    private static final Set<String> TRUSTED_LAUNCHER_KEYS = Set.of(
            "PATH",
            "PATHEXT",
            "SystemRoot",
            "windir",
            "SystemDrive",
            "ComSpec",
            "TEMP",
            "TMP",
            "USERPROFILE",
            "APPDATA",
            "LOCALAPPDATA",
            "ProgramData",
            "USERNAME",
            "USERDOMAIN",
            "COMPUTERNAME",
            "PSModuleAnalysisCachePath"
    );

    /** Lower-case fragments of a name that mark a secret; compared case-insensitively. */
    private static final Set<String> SECRET_FRAGMENTS = Set.of("token", "secret", "password", "key", "credential");

    /** Name prefixes that are never forwarded, except the explicit exceptions below. */
    private static final Set<String> DENIED_PREFIXES = Set.of("minos_", "github_", "npm_");

    /** Not secrets, although they match a denied prefix. */
    private static final Set<String> DENIED_PREFIX_EXCEPTIONS = Set.of("npm_config_cache");

    private ProviderProcessEnvironment() {
    }

    /**
     * True for a name that looks like a secret or a MINOS/CI credential. The pattern wins over every list:
     * an inherited variable that matches is dropped even when an allowlist names it, and so is a declared
     * variable that matches. The allowlists contain no such name either, which a test enforces.
     */
    static boolean isDeniedName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (DENIED_PREFIX_EXCEPTIONS.contains(lower)) {
            return false;
        }
        return DENIED_PREFIXES.stream().anyMatch(lower::startsWith)
                || SECRET_FRAGMENTS.stream().anyMatch(lower::contains);
    }

    /**
     * Replaces the environment of {@code builder} with the minimum a trusted launcher needs, plus {@code declared}.
     * Every trusted PowerShell program MINOS starts uses it: the sandbox launchers and the Windows path
     * identity helper.
     */
    public static void applyForTrustedLauncher(ProcessBuilder builder, Map<String, String> declared) {
        Objects.requireNonNull(builder, "builder");
        Objects.requireNonNull(declared, "declared");
        Map<String, String> environment = builder.environment();
        Map<String, String> trusted = trustedLauncherEnvironment(
                environment, declared, ProviderProcessEnvironment::isTrustedLauncherKey);
        environment.clear();
        environment.putAll(trusted);
    }

    /** The trusted-launcher environment built from {@code inherited}; {@code allowed} names the inherited keys kept. */
    static Map<String, String> trustedLauncherEnvironment(
            Map<String, String> inherited,
            Map<String, String> declared,
            Predicate<String> allowed
    ) {
        LinkedHashMap<String, String> kept = new LinkedHashMap<>();
        inherited.forEach((key, value) -> {
            if (key != null && value != null && allowed.test(key) && !isDeniedName(key)) {
                kept.put(key, value);
            }
        });
        putDeclared(kept, declared);
        return kept;
    }

    private static boolean isTrustedLauncherKey(String candidate) {
        return TRUSTED_LAUNCHER_KEYS.stream().anyMatch(allowed -> allowed.equalsIgnoreCase(candidate));
    }

    /** Every name of the three inherited allowlists, for the test that keeps them free of secret-looking names. */
    static Stream<String> inheritedAllowlistNames() {
        return Stream.of(COMMON_SAFE_INHERITED_KEYS, WINDOWS_SAFE_INHERITED_KEYS, TRUSTED_LAUNCHER_KEYS)
                .flatMap(Set::stream);
    }

    static void apply(ProcessBuilder builder, Map<String, String> declared) {
        Objects.requireNonNull(builder, "builder");
        Map<String, String> environment = builder.environment();
        Map<String, String> sanitized = sanitize(environment, declared);
        environment.clear();
        environment.putAll(sanitized);
    }

    public static Map<String, String> sanitize(
            Map<String, String> inherited,
            Map<String, String> declared
    ) {
        return sanitize(inherited, declared, ProviderProcessEnvironment::isSafeInheritedKey);
    }

    /** The provider environment built from {@code inherited}; {@code allowed} names the inherited keys kept. */
    static Map<String, String> sanitize(
            Map<String, String> inherited,
            Map<String, String> declared,
            Predicate<String> allowed
    ) {
        Objects.requireNonNull(inherited, "inherited");
        Objects.requireNonNull(declared, "declared");
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        inherited.forEach((key, value) -> {
            if (key != null && value != null && allowed.test(key) && !isDeniedName(key)) {
                result.put(key, value);
            }
        });
        putDeclared(result, declared);
        return Map.copyOf(result);
    }

    /** Adds the declared variables, except those whose name matches the deny pattern. */
    private static void putDeclared(Map<String, String> target, Map<String, String> declared) {
        declared.forEach((key, value) -> {
            if (!isDeniedName(key)) {
                target.put(key, value);
            }
        });
    }

    private static boolean isSafeInheritedKey(String candidate) {
        if (CommandLocator.isWindows()) {
            return isWindowsDriveCurrentDirectory(candidate)
                    || COMMON_SAFE_INHERITED_KEYS.stream().anyMatch(key -> key.equalsIgnoreCase(candidate))
                    || WINDOWS_SAFE_INHERITED_KEYS.stream().anyMatch(key -> key.equalsIgnoreCase(candidate));
        }
        return COMMON_SAFE_INHERITED_KEYS.contains(candidate);
    }

    /** Windows environment blocks may contain per-drive current-directory pseudo variables such as =C:. */
    private static boolean isWindowsDriveCurrentDirectory(String candidate) {
        return candidate.length() == 3
                && candidate.charAt(0) == '='
                && Character.isLetter(candidate.charAt(1))
                && candidate.charAt(2) == ':';
    }
}
