package com.minos.runtime.local;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Builds the environment exposed to an external provider process.
 *
 * <p>Provider code is treated as untrusted. The parent MINOS process may carry database passwords,
 * hosted tokens, CI credentials or operator secrets, so provider processes must never inherit the
 * parent environment wholesale. Only the runtime/OS allowlists below are inherited implicitly;
 * provider-specific values must be declared explicitly by {@link IndexerProcessPlan#environment()}.</p>
 */
final class ProviderProcessEnvironment {

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
            "COMPUTERNAME"
    );

    private ProviderProcessEnvironment() {
    }

    /** Replaces the environment of {@code builder} with the minimum a trusted launcher needs, plus {@code declared}. */
    static void applyForTrustedLauncher(ProcessBuilder builder, Map<String, String> declared) {
        Objects.requireNonNull(builder, "builder");
        Objects.requireNonNull(declared, "declared");
        Map<String, String> environment = builder.environment();
        LinkedHashMap<String, String> kept = new LinkedHashMap<>();
        environment.forEach((key, value) -> {
            if (key != null && value != null
                    && TRUSTED_LAUNCHER_KEYS.stream().anyMatch(allowed -> allowed.equalsIgnoreCase(key))) {
                kept.put(key, value);
            }
        });
        environment.clear();
        environment.putAll(kept);
        environment.putAll(declared);
    }

    static void apply(ProcessBuilder builder, Map<String, String> declared) {
        Objects.requireNonNull(builder, "builder");
        Map<String, String> environment = builder.environment();
        Map<String, String> sanitized = sanitize(environment, declared);
        environment.clear();
        environment.putAll(sanitized);
    }

    static Map<String, String> sanitize(
            Map<String, String> inherited,
            Map<String, String> declared
    ) {
        Objects.requireNonNull(inherited, "inherited");
        Objects.requireNonNull(declared, "declared");
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        inherited.forEach((key, value) -> {
            if (key != null && value != null && isSafeInheritedKey(key)) {
                result.put(key, value);
            }
        });
        result.putAll(declared);
        return Map.copyOf(result);
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
