package com.minos.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclFileAttributeView;

/**
 * What a given path's filesystem actually exposes for privacy enforcement. Production code
 * always uses {@link #real()}; tests may substitute a fake via
 * {@link PrivateLocalStorage#useForTesting(CapabilityProbe)} to deterministically exercise the POSIX, ACL and
 * unsupported-filesystem branches without depending on the real OS/filesystem under CI.
 *
 * <p>Public so that tests in other modules that build on {@code PrivateLocalStorage} (e.g.
 * {@code MinosApplication.open}) can inject the same deterministic fault without depending on
 * minos-engine's test sources or duplicating this seam. It is a fault-injection hook, not part
 * of the storage policy itself -- production code never references it, and {@link
 * PrivateLocalStorage#useForTesting} refuses to install one outside a test runtime (see there for why that is, and
 * is not, a security boundary).</p>
 */
public interface CapabilityProbe {
    boolean supportsPosix(Path target);

    AclFileAttributeView aclView(Path target);

    /**
     * Makes the DACL of {@code target}, which already holds its final explicit entries, stop
     * inheriting from its parent. Only a platform whose inheritance Java cannot switch off through
     * {@link AclFileAttributeView} has anything to do here: the default does nothing, which is what a
     * test double wants.
     */
    default void protectFromInheritance(Path target) throws IOException {
    }

    /**
     * Fails if {@code target} carries a conditional DENY entry, or if its DACL cannot be read to find out.
     * Only a platform whose DACL Java cannot fully read has anything to do here: the default does nothing,
     * which is what a test double wants.
     */
    default void requireNoConditionalDeny(Path target) throws IOException {
    }

    static CapabilityProbe real() {
        return new CapabilityProbe() {
            @Override
            public boolean supportsPosix(Path target) {
                return target.getFileSystem().supportedFileAttributeViews().contains("posix");
            }

            @Override
            public AclFileAttributeView aclView(Path target) {
                return Files.getFileAttributeView(target, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            }

            @Override
            public void protectFromInheritance(Path target) throws IOException {
                PlatformAccessControlDiscovery.current().protectFromInheritance(target);
            }

            @Override
            public void requireNoConditionalDeny(Path target) throws IOException {
                PlatformAccessControlDiscovery.current().requireNoConditionalDeny(target);
            }
        };
    }
}
