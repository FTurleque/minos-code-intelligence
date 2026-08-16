package com.minos.io;

import java.nio.file.Path;
import java.nio.file.attribute.AclFileAttributeView;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Deterministic {@link PrivateLocalStorage.CapabilityProbe} double for fault injection.
 *
 * <p>Simulates the filesystems {@link PrivateLocalStorage} must cope with, without depending on
 * what the CI runner's real OS happens to expose:</p>
 * <ul>
 *   <li>{@link #unsupported()} -- neither POSIX permissions nor an ACL view (e.g. some network or
 *       exotic filesystem mounts): every enforcement entry point must fail closed.</li>
 *   <li>{@link #aclOnly(Function)} -- no POSIX, but an ACL view is available (models Windows/NTFS on
 *       any OS the tests happen to run on). The same fake view is returned for repeat lookups of the
 *       same path, so a {@code harden()} mutation is visible to the following {@code verify()}.</li>
 * </ul>
 */
final class FakeCapabilityProbe implements PrivateLocalStorage.CapabilityProbe {

    private final boolean posixSupported;
    private final Function<Path, AclFileAttributeView> aclFactory;
    private final Map<Path, AclFileAttributeView> views = new LinkedHashMap<>();

    private FakeCapabilityProbe(boolean posixSupported, Function<Path, AclFileAttributeView> aclFactory) {
        this.posixSupported = posixSupported;
        this.aclFactory = Objects.requireNonNull(aclFactory, "aclFactory");
    }

    static FakeCapabilityProbe unsupported() {
        return new FakeCapabilityProbe(false, target -> null);
    }

    static FakeCapabilityProbe aclOnly(Function<Path, AclFileAttributeView> aclFactory) {
        return new FakeCapabilityProbe(false, aclFactory);
    }

    @Override
    public boolean supportsPosix(Path target) {
        return posixSupported;
    }

    @Override
    public synchronized AclFileAttributeView aclView(Path target) {
        return views.computeIfAbsent(target, aclFactory);
    }

    /** The fake ACL view created for {@code target}, or {@code null} if never looked up. */
    synchronized AclFileAttributeView viewOf(Path target) {
        return views.get(target);
    }

    synchronized Map<Path, AclFileAttributeView> views() {
        return new LinkedHashMap<>(views);
    }
}
