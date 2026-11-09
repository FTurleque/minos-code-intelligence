package com.minos.io;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
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
        assertTrue(WriteDenyDiagnosis.carriesWriteDenyForOwner(List.of(deny(OWNER)), OWNER));
    }

    @Test
    void aWriteDenyOnAGroupIsAssumedToApplyBecauseMembershipIsUnknown() {
        assertTrue(WriteDenyDiagnosis.carriesWriteDenyForOwner(List.of(deny(new Group("Users"))), OWNER));
    }

    @Test
    void aWriteDenyOnAnotherUserDoesNotApply() {
        assertFalse(WriteDenyDiagnosis.carriesWriteDenyForOwner(List.of(deny(new User("someone-else"))), OWNER));
    }

    @Test
    void anInheritOnlyDenyDoesNotApplyToTheObjectItself() {
        assertFalse(WriteDenyDiagnosis.carriesWriteDenyForOwner(
                List.of(deny(OWNER, AclEntryFlag.INHERIT_ONLY, AclEntryFlag.FILE_INHERIT)), OWNER));
    }

    @Test
    void aDenyOfReadRightsOnlyIsNotAWriteProtection() {
        AclEntry readDeny = AclEntry.newBuilder().setType(AclEntryType.DENY).setPrincipal(OWNER)
                .setPermissions(EnumSet.of(AclEntryPermission.READ_DATA)).build();

        assertFalse(WriteDenyDiagnosis.carriesWriteDenyForOwner(List.of(readDeny), OWNER));
    }

    @Test
    void anAclViewThatCannotBeReadLeavesTheOriginalRefusalUntouched() {
        AccessDeniedException denied = new AccessDeniedException("original");
        AclFileAttributeView unreadable = new FailingAclView(new IOException("acl cannot be read"));

        IOException explained = WriteDenyDiagnosis.explainDenied(
                denied, location -> unreadable, java.nio.file.Path.of("location"));

        assertSame(denied, explained, "nothing more precise than the original refusal can be said");
    }

    @Test
    void aWriteDenyOnTheLocationIsWordedWithoutThePathWhileAnUnreadableOneIsSkipped() {
        AccessDeniedException denied = new AccessDeniedException("original");
        AclFileAttributeView unreadable = new FailingAclView(new IOException("acl cannot be read"));
        AclFileAttributeView denying = new FakeAclFileAttributeView(OWNER, List.of(deny(OWNER)));

        IOException explained = WriteDenyDiagnosis.explainDenied(denied,
                location -> location.getFileName().toString().equals("unreadable") ? unreadable : denying,
                java.nio.file.Path.of("unreadable"), java.nio.file.Path.of("denying"));

        assertTrue(explained.getMessage().contains("write-protected by an explicit deny entry"), explained.getMessage());
        assertFalse(explained.getMessage().contains("denying"), "the location is named");
    }

    /** An ACL view whose every read fails with {@code failure}. */
    private record FailingAclView(IOException failure) implements AclFileAttributeView {
        @Override
        public List<AclEntry> getAcl() throws IOException {
            throw failure;
        }

        @Override
        public void setAcl(List<AclEntry> acl) throws IOException {
            throw failure;
        }

        @Override
        public String name() {
            return "acl";
        }

        @Override
        public UserPrincipal getOwner() throws IOException {
            throw failure;
        }

        @Override
        public void setOwner(UserPrincipal owner) throws IOException {
            throw failure;
        }
    }
}
