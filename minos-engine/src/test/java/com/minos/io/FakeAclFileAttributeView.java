package com.minos.io;

import java.io.IOException;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic, in-memory {@link AclFileAttributeView} double. Lets tests exercise
 * {@link PrivateLocalStorage}'s ACL branch -- correct owner-only ACL, a foreign grant, hardening
 * that narrows an inherited ACL -- on any OS, not just a real Windows/NTFS machine.
 *
 * <p>{@code owner} must be the <em>real</em> owner of the backing path (as {@link
 * java.nio.file.Files#getOwner} would report it), because {@link PrivateLocalStorage} hardens an
 * ACL using that real principal. A foreign entry, by contrast, uses {@link FakeUserPrincipal} since
 * it only needs an identity distinct from the real owner.
 */
final class FakeAclFileAttributeView implements AclFileAttributeView {

    private final UserPrincipal owner;
    private List<AclEntry> acl;

    FakeAclFileAttributeView(UserPrincipal owner, List<AclEntry> initialAcl) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.acl = new ArrayList<>(initialAcl);
    }

    static FakeAclFileAttributeView ownerOnly(UserPrincipal owner) {
        return new FakeAclFileAttributeView(owner, List.of(ownerAllowEntry(owner)));
    }

    static FakeAclFileAttributeView withForeignGrant(UserPrincipal owner) {
        return new FakeAclFileAttributeView(owner, List.of(
                ownerAllowEntry(owner),
                allowEntry(new FakeUserPrincipal("everyone"))));
    }

    /**
     * An ACL view that grants a foreign principal access and whose {@code setAcl} is a permanent
     * no-op -- simulating a broken hardening implementation where the write silently fails to take
     * effect. Used to prove {@code verify()} independently catches what {@code harden()} could not
     * fix, rather than trusting that a harden call without an exception means success.
     */
    static AclFileAttributeView stuckWithForeignGrant(UserPrincipal owner) {
        List<AclEntry> fixed = List.of(ownerAllowEntry(owner), allowEntry(new FakeUserPrincipal("everyone")));
        return new AclFileAttributeView() {
            @Override
            public List<AclEntry> getAcl() {
                return fixed;
            }

            @Override
            public void setAcl(List<AclEntry> acl) {
                // intentionally does nothing
            }

            @Override
            public String name() {
                return "acl";
            }

            @Override
            public UserPrincipal getOwner() {
                return owner;
            }

            @Override
            public void setOwner(UserPrincipal newOwner) throws IOException {
                throw new IOException("changing owner is not supported by this test double");
            }
        };
    }

    static AclEntry ownerAllowEntry(UserPrincipal principal) {
        return allowEntry(principal);
    }

    private static AclEntry allowEntry(UserPrincipal principal) {
        return AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(principal)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                .build();
    }

    @Override
    public List<AclEntry> getAcl() {
        return new ArrayList<>(acl);
    }

    @Override
    public void setAcl(List<AclEntry> acl) {
        this.acl = new ArrayList<>(acl);
    }

    @Override
    public String name() {
        return "acl";
    }

    @Override
    public UserPrincipal getOwner() {
        return owner;
    }

    @Override
    public void setOwner(UserPrincipal owner) throws IOException {
        throw new IOException("changing owner is not supported by this test double");
    }

    /** Minimal deterministic principal representing an identity other than the real path owner. */
    static final class FakeUserPrincipal implements UserPrincipal {
        private final String name;

        FakeUserPrincipal(String name) {
            this.name = Objects.requireNonNull(name, "name");
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof FakeUserPrincipal that && name.equals(that.name);
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }

        @Override
        public String toString() {
            return name;
        }
    }
}
