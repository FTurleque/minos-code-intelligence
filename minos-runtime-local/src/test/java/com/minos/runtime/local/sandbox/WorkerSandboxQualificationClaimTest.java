package com.minos.runtime.local.sandbox;

import com.minos.remote.DistributedIndexing.WorkerIsolation;
import com.minos.runtime.local.sandbox.WorkerResourceContainment.Disposition;
import com.minos.runtime.local.sandbox.WorkerSandboxBackend.NetworkGuarantee;
import com.minos.runtime.local.sandbox.WorkerSandboxQualification.NetworkDenyDisposition;
import com.minos.runtime.local.sandbox.WorkerSandboxQualification.Platform;
import com.minos.runtime.local.sandbox.WorkerSandboxQualification.PlatformDisposition;
import com.minos.runtime.local.sandbox.WorkerSandboxQualification.TrustDisposition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Les revendications de {@link WorkerSandboxQualification} : ce qui est refusé à la construction et ce
 * qu'une qualification complète autorise. La plateforme courante est lue par l'API de la classe,
 * jamais devinée par le test.
 */
class WorkerSandboxQualificationClaimTest {

    private static final WorkerIsolation ISOLATION = WorkerIsolation.PROCESS_EPHEMERAL_WORKSPACE;

    private static WorkerResourceContainment hardContainment() {
        return new WorkerResourceContainment(
                "hard-boundary",
                Disposition.OS_ENFORCED,
                Disposition.OS_ENFORCED,
                Disposition.OS_ENFORCED,
                Disposition.OS_ENFORCED,
                Disposition.OS_ENFORCED,
                Disposition.OS_ENFORCED,
                Disposition.OS_ENFORCED,
                Disposition.OS_ENFORCED,
                List.of("FIXTURE_HARD_BOUNDARY"));
    }

    private static Platform anotherPlatform() {
        Platform current = WorkerSandboxQualification.currentPlatform();
        return current == Platform.LINUX ? Platform.WINDOWS : Platform.LINUX;
    }

    private static WorkerSandboxQualification supported(Map<Platform, PlatformDisposition> platforms) {
        return new WorkerSandboxQualification(
                "hard-boundary",
                ISOLATION,
                NetworkGuarantee.OS_ENFORCED,
                NetworkDenyDisposition.QUALIFIED,
                TrustDisposition.UNTRUSTED_CODE_SUPPORTED,
                hardContainment(),
                platforms,
                List.of("FIXTURE_LIMITATION"));
    }

    @Test
    void aBackendWithoutAnIdentifierIsRefused() {
        WorkerResourceContainment containment = WorkerResourceContainment.none("anonymous");
        Map<Platform, PlatformDisposition> platforms = Map.of();
        List<String> limitations = List.of();

        IllegalArgumentException blank = assertThrows(IllegalArgumentException.class,
                () -> new WorkerSandboxQualification(
                        "  ", ISOLATION, NetworkGuarantee.NONE, NetworkDenyDisposition.FAIL_CLOSED_NOT_ENFORCED,
                        TrustDisposition.UNTRUSTED_CODE_UNSUPPORTED, containment, platforms, limitations));
        assertThrows(IllegalArgumentException.class,
                () -> new WorkerSandboxQualification(
                        null, ISOLATION, NetworkGuarantee.NONE, NetworkDenyDisposition.FAIL_CLOSED_NOT_ENFORCED,
                        TrustDisposition.UNTRUSTED_CODE_UNSUPPORTED, containment, platforms, limitations));

        assertEquals("backendId must not be blank", blank.getMessage());
    }

    @Test
    void absentPlatformsAndLimitationsMeanNoneAndNeverACurrentPlatformQualification() {
        WorkerSandboxQualification qualification = new WorkerSandboxQualification(
                "undeclared",
                ISOLATION,
                NetworkGuarantee.OS_ENFORCED,
                NetworkDenyDisposition.QUALIFIED,
                TrustDisposition.UNTRUSTED_CODE_UNSUPPORTED,
                hardContainment(),
                null,
                null);

        assertEquals(Map.of(), qualification.platforms());
        assertEquals(List.of(), qualification.limitations());
        assertFalse(qualification.qualifiedForCurrentPlatform(),
                "a platform that is not declared is not applicable, never qualified");
        assertFalse(qualification.sandboxClaimPermitted());
        assertFalse(qualification.managedLocalProviderClaimPermitted(),
                "even a hard boundary claims nothing on a platform it was not qualified for");
    }

    @Test
    void untrustedCodeSupportIsRefusedWithoutAQualifiedNetworkDenial() {
        WorkerResourceContainment containment = hardContainment();
        Map<Platform, PlatformDisposition> platforms =
                Map.of(WorkerSandboxQualification.currentPlatform(), PlatformDisposition.QUALIFIED);
        List<String> limitations = List.of();

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> new WorkerSandboxQualification(
                        "no-network-denial", ISOLATION, NetworkGuarantee.OS_ENFORCED,
                        NetworkDenyDisposition.FAIL_CLOSED_NOT_ENFORCED, TrustDisposition.UNTRUSTED_CODE_SUPPORTED,
                        containment, platforms, limitations));

        assertEquals("untrusted-code support requires qualified network denial", refusal.getMessage());
    }

    @Test
    void untrustedCodeSupportIsRefusedWithoutAnyQualifiedPlatform() {
        Map<Platform, PlatformDisposition> blockedEverywhere = Map.of(
                Platform.WINDOWS, PlatformDisposition.BLOCKED_NO_RESTRICTED_TOKEN_JOB_OBJECT_BACKEND,
                Platform.LINUX, PlatformDisposition.BLOCKED_NO_NAMESPACE_SECCOMP_BACKEND);
        Map<Platform, PlatformDisposition> undeclared = Map.of();

        IllegalArgumentException blocked = assertThrows(IllegalArgumentException.class,
                () -> supported(blockedEverywhere));
        assertThrows(IllegalArgumentException.class, () -> supported(undeclared));

        assertEquals("untrusted-code support requires at least one qualified platform", blocked.getMessage());
    }

    @Test
    void aFullyOsEnforcedBackendQualifiedHereMayClaimTheSandboxAndIsNotDowngraded() {
        WorkerSandboxQualification qualification = supported(
                Map.of(WorkerSandboxQualification.currentPlatform(), PlatformDisposition.QUALIFIED));

        assertEquals(TrustDisposition.UNTRUSTED_CODE_SUPPORTED, qualification.trustDisposition());
        assertEquals(List.of("FIXTURE_LIMITATION"), qualification.limitations(),
                "a complete hard boundary receives no fail-closed limitation");
        assertTrue(qualification.qualifiedForCurrentPlatform());
        assertTrue(qualification.sandboxClaimPermitted());
        assertTrue(qualification.managedLocalProviderClaimPermitted());
    }

    @Test
    void aQualificationEarnedOnAnotherPlatformClaimsNothingHere() {
        WorkerSandboxQualification qualification = supported(Map.of(
                anotherPlatform(), PlatformDisposition.QUALIFIED,
                WorkerSandboxQualification.currentPlatform(),
                PlatformDisposition.BLOCKED_NO_AGGREGATE_RESOURCE_JOB_BOUNDARY));

        assertEquals(TrustDisposition.UNTRUSTED_CODE_SUPPORTED, qualification.trustDisposition());
        assertFalse(qualification.qualifiedForCurrentPlatform());
        assertFalse(qualification.sandboxClaimPermitted(),
                "a claim is bound to the platform MINOS runs on, not to the best platform of the backend");
        assertFalse(qualification.managedLocalProviderClaimPermitted());
    }

    @Test
    void anOsEnforcedNetworkGuaranteeWithoutQualifiedDenialDoesNotPermitTheManagedLocalClaim() {
        WorkerSandboxQualification qualification = new WorkerSandboxQualification(
                "guarantee-without-qualification",
                ISOLATION,
                NetworkGuarantee.OS_ENFORCED,
                NetworkDenyDisposition.FAIL_CLOSED_NOT_ENFORCED,
                TrustDisposition.UNTRUSTED_CODE_UNSUPPORTED,
                hardContainment(),
                Map.of(WorkerSandboxQualification.currentPlatform(), PlatformDisposition.QUALIFIED),
                List.of());

        assertTrue(qualification.qualifiedForCurrentPlatform());
        assertFalse(qualification.managedLocalProviderClaimPermitted());
        assertFalse(qualification.sandboxClaimPermitted());
    }

    @Test
    void aBackendThatDoesNotSupportUntrustedCodeNeverClaimsTheSandboxEvenWithAHardBoundary() {
        WorkerSandboxQualification qualification = new WorkerSandboxQualification(
                "trusted-code-only",
                ISOLATION,
                NetworkGuarantee.OS_ENFORCED,
                NetworkDenyDisposition.QUALIFIED,
                TrustDisposition.UNTRUSTED_CODE_UNSUPPORTED,
                hardContainment(),
                Map.of(WorkerSandboxQualification.currentPlatform(), PlatformDisposition.QUALIFIED),
                List.of());

        assertFalse(qualification.sandboxClaimPermitted());
        assertTrue(qualification.managedLocalProviderClaimPermitted(),
                "the narrower managed-local contract does not depend on the untrusted-code disposition");
    }

    @Test
    void theProcessOnlyQualificationIsBlockedOnEveryPlatformAndProhibitsEveryClaim() {
        WorkerSandboxQualification qualification =
                WorkerSandboxQualification.nativeProcessOnly("native-fixture", ISOLATION);

        assertEquals("native-fixture", qualification.backendId());
        assertEquals("native-fixture", qualification.containment().boundaryId());
        assertEquals(NetworkGuarantee.NONE, qualification.networkGuarantee());
        assertEquals(PlatformDisposition.NOT_APPLICABLE, qualification.platforms().get(Platform.OTHER));
        assertTrue(qualification.platforms().values().stream()
                .noneMatch(value -> value == PlatformDisposition.QUALIFIED));
        assertEquals("WORKER_SANDBOX_CLAIM_PROHIBITED", qualification.limitations().getLast());
        assertFalse(qualification.qualifiedForCurrentPlatform());
        assertFalse(qualification.sandboxClaimPermitted());
        assertFalse(qualification.managedLocalProviderClaimPermitted());
    }
}
