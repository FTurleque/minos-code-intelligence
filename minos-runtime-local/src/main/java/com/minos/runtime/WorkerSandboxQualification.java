package com.minos.runtime;

import com.minos.remote.DistributedIndexing.WorkerIsolation;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Explicit, machine-readable trust and platform disposition for one worker backend. */
public record WorkerSandboxQualification(
        String backendId,
        WorkerIsolation isolation,
        WorkerSandboxBackend.NetworkGuarantee networkGuarantee,
        NetworkDenyDisposition networkDeny,
        TrustDisposition trustDisposition,
        Map<Platform, PlatformDisposition> platforms,
        List<String> limitations
) {
    public WorkerSandboxQualification {
        if (backendId == null || backendId.isBlank()) {
            throw new IllegalArgumentException("backendId must not be blank");
        }
        Objects.requireNonNull(isolation, "isolation");
        Objects.requireNonNull(networkGuarantee, "networkGuarantee");
        Objects.requireNonNull(networkDeny, "networkDeny");
        Objects.requireNonNull(trustDisposition, "trustDisposition");
        platforms = platforms == null ? Map.of() : Map.copyOf(platforms);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
        if (networkDeny == NetworkDenyDisposition.QUALIFIED
                && networkGuarantee != WorkerSandboxBackend.NetworkGuarantee.OS_ENFORCED) {
            throw new IllegalArgumentException("qualified network denial requires an OS-enforced guarantee");
        }
        if (trustDisposition == TrustDisposition.UNTRUSTED_CODE_SUPPORTED
                && networkDeny != NetworkDenyDisposition.QUALIFIED) {
            throw new IllegalArgumentException("untrusted-code support requires qualified network denial");
        }
        if (trustDisposition == TrustDisposition.UNTRUSTED_CODE_SUPPORTED
                && platforms.values().stream().noneMatch(value -> value == PlatformDisposition.QUALIFIED)) {
            throw new IllegalArgumentException("untrusted-code support requires at least one qualified platform");
        }
    }

    public boolean sandboxClaimPermitted() {
        return trustDisposition == TrustDisposition.UNTRUSTED_CODE_SUPPORTED
                && networkDeny == NetworkDenyDisposition.QUALIFIED
                && qualifiedForCurrentPlatform();
    }

    public boolean qualifiedForCurrentPlatform() {
        return platforms.getOrDefault(currentPlatform(), PlatformDisposition.NOT_APPLICABLE)
                == PlatformDisposition.QUALIFIED;
    }

    public static Platform currentPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return Platform.WINDOWS;
        if (os.contains("linux")) return Platform.LINUX;
        return Platform.OTHER;
    }

    public static WorkerSandboxQualification nativeProcessOnly(String backendId, WorkerIsolation isolation) {
        return new WorkerSandboxQualification(
                backendId,
                isolation,
                WorkerSandboxBackend.NetworkGuarantee.NONE,
                NetworkDenyDisposition.FAIL_CLOSED_NOT_ENFORCED,
                TrustDisposition.UNTRUSTED_CODE_UNSUPPORTED,
                Map.of(
                        Platform.WINDOWS, PlatformDisposition.BLOCKED_NO_RESTRICTED_TOKEN_JOB_OBJECT_BACKEND,
                        Platform.LINUX, PlatformDisposition.BLOCKED_NO_NAMESPACE_SECCOMP_BACKEND,
                        Platform.OTHER, PlatformDisposition.NOT_APPLICABLE),
                List.of(
                        "WORKER_PROCESS_SEPARATION_ONLY",
                        "WORKER_FILESYSTEM_CONFINEMENT_APPLICATION_LEVEL_ONLY",
                        "WORKER_NETWORK_DENY_NOT_OS_ENFORCED",
                        "WORKER_UNTRUSTED_CODE_NOT_SUPPORTED",
                        "WORKER_SANDBOX_CLAIM_PROHIBITED"));
    }

    public enum NetworkDenyDisposition {
        QUALIFIED,
        FAIL_CLOSED_NOT_ENFORCED
    }

    public enum TrustDisposition {
        UNTRUSTED_CODE_SUPPORTED,
        UNTRUSTED_CODE_UNSUPPORTED
    }

    public enum Platform {
        WINDOWS,
        LINUX,
        OTHER
    }

    public enum PlatformDisposition {
        QUALIFIED,
        BLOCKED_NO_RESTRICTED_TOKEN_JOB_OBJECT_BACKEND,
        BLOCKED_NO_NAMESPACE_SECCOMP_BACKEND,
        NOT_APPLICABLE
    }
}
