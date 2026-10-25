package com.minos.io;

import java.io.IOException;
import java.nio.file.Path;

/**
 * The platform mechanism behind the private storage of a file system with access control lists, which Java's
 * {@link java.nio.file.attribute.AclFileAttributeView} cannot drive alone: it cannot mark a DACL as protected from
 * inheritance, and it does not show conditional entries.
 *
 * <p>The engine declares the port; the local runtime provides the Windows implementation, registered in
 * {@code META-INF/services} and discovered once per process by {@link PrivateLocalStorage} through the class
 * loader that loaded MINOS (ADR 0068, AUD-ARC-05). It is consulted only on a file system with ACLs, never on a
 * POSIX one. With no implementation, or more than one, every operation fails: private storage is refused rather
 * than left inheriting. An implementation that has nothing to do on the current platform returns without
 * acting.</p>
 *
 * <p>Implementations launch no shell, take a path checked beforehand and fail rather than let an object through;
 * their refusals name no path.</p>
 */
public interface PlatformAccessControl {

    /**
     * Makes the DACL of {@code target}, which already holds its final explicit entries, stop inheriting from its
     * parent.
     */
    void protectFromInheritance(Path target) throws IOException;

    /**
     * Fails if {@code target} carries a conditional DENY entry, or if its DACL cannot be read to find out. Called
     * before the DACL is rewritten, so that a refusal written by an administrator is never removed.
     */
    void requireNoConditionalDeny(Path target) throws IOException;
}
