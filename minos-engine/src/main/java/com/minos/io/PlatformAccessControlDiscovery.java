package com.minos.io;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * Discovers the {@link PlatformAccessControl} port of this process (ADR 0068) by the class loader of this class, never
 * by the thread context class loader (ADR 0042, AUD-ARC-04), and keeps it.
 */
final class PlatformAccessControlDiscovery {

    private PlatformAccessControlDiscovery() {
    }

    /** The port of this process, discovered on first use and kept. */
    private static final class Discovered {
        static final PlatformAccessControl INSTANCE =
                discover(PlatformAccessControlDiscovery.class.getClassLoader());

        private Discovered() {
        }
    }

    /** The platform access control of this process (see {@link PlatformAccessControl}). */
    static PlatformAccessControl current() {
        return Discovered.INSTANCE;
    }

    /**
     * The single implementation registered for {@code loader}, or one that refuses every operation when there is
     * none, more than one, or one that cannot be loaded: choosing between two would let either decide what private
     * storage means. The thread context class loader is never used (ADR 0042, AUD-ARC-04).
     */
    static PlatformAccessControl discover(ClassLoader loader) {
        List<PlatformAccessControl> found = new ArrayList<>();
        try {
            ServiceLoader.load(PlatformAccessControl.class, loader).forEach(found::add);
        } catch (ServiceConfigurationError _) {
            return refusing("a platform access control for private storage cannot be loaded");
        }
        if (found.isEmpty()) {
            return refusing("no platform access control is available to make private storage private on this "
                    + "file system");
        }
        if (found.size() > 1) {
            return refusing("several platform access controls are registered for private storage; none is chosen");
        }
        return found.getFirst();
    }

    private static PlatformAccessControl refusing(String reason) {
        return new PlatformAccessControl() {
            @Override
            public void protectFromInheritance(Path target) throws IOException {
                throw new IOException(reason);
            }

            @Override
            public void requireNoConditionalDeny(Path target) throws IOException {
                throw new IOException(reason);
            }
        };
    }
}
