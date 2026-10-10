package com.minos.runtime.local;

import org.opentest4j.AssertionFailedError;
import org.opentest4j.TestAbortedException;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Single decision point for "is the OS confinement this test qualifies actually available?".
 *
 * <p>Developer workstations may lack bubblewrap, a delegated cgroup v2 root or an AppContainer
 * runtime, and then skip the qualification. CI and release qualification set
 * {@code -Dminos.sandbox.tests.required=true}, which turns an unavailable confinement into a hard
 * failure so the proof of containment can never disappear silently (same contract as
 * {@code minos.postgresql.tests.required}).</p>
 *
 * <p>Platform applicability is not decided here: a test that only makes sense on one operating
 * system is restricted with {@code @EnabledOnOs}, so the required property never turns "this test
 * does not apply to this OS" into a failure.</p>
 */
final class SandboxTestSupport {

    static final String REQUIRED_PROPERTY = "minos.sandbox.tests.required";

    private SandboxTestSupport() {
    }

    static boolean required() {
        return Boolean.getBoolean(REQUIRED_PROPERTY);
    }

    /**
     * Fails when the confinement is required and unavailable, aborts (visible skip) when it is
     * unavailable but optional, and does nothing when it is available.
     */
    static void decide(boolean required, boolean available, Supplier<String> reason) {
        if (available) {
            return;
        }
        String message = reason.get();
        if (required) {
            throw new AssertionFailedError(REQUIRED_PROPERTY + "=true but the confinement this test qualifies is "
                    + "unavailable: " + message);
        }
        throw new TestAbortedException("Assumption failed: " + message);
    }

    /** The discovered sandbox backend, or a failure / skip according to {@link #REQUIRED_PROPERTY}. */
    static <T> T requireBackend(Optional<T> discovered, String what) {
        decide(required(), discovered.isPresent(), () -> what);
        return discovered.orElseThrow();
    }

    /** The delegated cgroup v2 root, or a failure / skip according to {@link #REQUIRED_PROPERTY}. */
    static Path requireDelegatedCgroupRoot() {
        Optional<Path> root = LinuxCgroupJob.delegatedRoot();
        decide(required(), root.isPresent(),
                () -> "a delegated cgroup v2 root is required; set " + LinuxCgroupJob.ROOT_ENVIRONMENT_VARIABLE);
        return root.orElseThrow();
    }

    /** A strong process-ownership capability, with the executor's own diagnostics as the reason. */
    static void requireStrongCapability(boolean strong, List<String> diagnostics) {
        decide(required(), strong, () -> String.join("; ", diagnostics));
    }
}
