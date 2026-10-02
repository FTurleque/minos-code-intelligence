package com.minos.io;

import org.junit.jupiter.api.Test;

import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which DENY entries justify saying "write-protected by an explicit deny entry". Any platform. */
class WriteDenyClassificationTest {

    private record User(String getName) implements UserPrincipal { }

    private record Group(String getName) implements GroupPrincipal { }

    private static final UserPrincipal OWNER = new User("owner");

    private static AclEntry deny(UserPrincipal who, AclEntryFlag... flags) {
        return AclEntry.newBuilder().setType(AclEntryType.DENY).setPrincipal(who)
                .setPermissions(AclEntryPermission.WRITE_DATA, AclEntryPermission.APPEND_DATA)
                .setFlags(flags).build();
    }

    @Test
    void aWriteDenyOnTheOwnerApplies() {
        assertTrue(PrivateLocalStorage.carriesWriteDenyForOwner(List.of(deny(OWNER)), OWNER));
    }

    @Test
    void aWriteDenyOnAGroupIsAssumedToApplyBecauseMembershipIsUnknown() {
        assertTrue(PrivateLocalStorage.carriesWriteDenyForOwner(List.of(deny(new Group("Users"))), OWNER));
    }

    @Test
    void aWriteDenyOnAnotherUserDoesNotApply() {
        assertFalse(PrivateLocalStorage.carriesWriteDenyForOwner(List.of(deny(new User("someone-else"))), OWNER));
    }

    @Test
    void anInheritOnlyDenyDoesNotApplyToTheObjectItself() {
        assertFalse(PrivateLocalStorage.carriesWriteDenyForOwner(
                List.of(deny(OWNER, AclEntryFlag.INHERIT_ONLY, AclEntryFlag.FILE_INHERIT)), OWNER));
    }

    @Test
    void aDenyOfReadRightsOnlyIsNotAWriteProtection() {
        AclEntry readDeny = AclEntry.newBuilder().setType(AclEntryType.DENY).setPrincipal(OWNER)
                .setPermissions(EnumSet.of(AclEntryPermission.READ_DATA)).build();

        assertFalse(PrivateLocalStorage.carriesWriteDenyForOwner(List.of(readDeny), OWNER));
    }
}
