package com.minos.runtime;

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

    private ProviderProcessEnvironment() {
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
            return COMMON_SAFE_INHERITED_KEYS.stream().anyMatch(key -> key.equalsIgnoreCase(candidate))
                    || WINDOWS_SAFE_INHERITED_KEYS.stream().anyMatch(key -> key.equalsIgnoreCase(candidate));
        }
        return COMMON_SAFE_INHERITED_KEYS.contains(candidate);
    }
}
