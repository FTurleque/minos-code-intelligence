package com.minos.runtime.local;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.attribute.UserPrincipal;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** SDDL as {@code icacls /save} writes it: SIDs, so the verdict does not depend on the machine's language. */
class SddlReplaceRightsTest {

    private static final String USER = "S-1-5-21-1-2-3-1001";
    private static final Set<String> TRUSTED = Set.of(USER, "S-1-5-18", "S-1-5-32-544");

    @Test
    void theDefaultProfileDaclOfAUserIsTrusted() {
        String sddl = "D:PAI(A;OICIID;FA;;;SY)(A;OICIID;FA;;;BA)(A;OICIID;0x1f01ff;;;" + USER + ")";

        assertNull(SddlReplaceRights.firstForeignReplaceGrant(sddl, TRUSTED));
    }

    @Test
    void readOnlyAccessOfAnotherGroupIsNotAGrantToReplace() {
        String sddl = "D:AI(A;OICIID;FA;;;SY)(A;OICI;0x1200a9;;;S-1-5-21-9-9-9-1500)(A;OICIID;FRFX;;;BU)";

        assertNull(SddlReplaceRights.firstForeignReplaceGrant(sddl, TRUSTED));
    }

    @Test
    void modifyRightsOfEveryoneAreAGrantToReplace() {
        String sddl = "D:AI(A;OICI;0x1301bf;;;WD)(A;OICIID;FA;;;SY)";

        assertEquals("WD", SddlReplaceRights.firstForeignReplaceGrant(sddl, TRUSTED));
    }

    @Test
    void anUnresolvedSidWithModifyRightsIsAGrantToReplace() {
        String sddl = "D:AI(A;OICIID;0x1301bf;;;S-1-5-21-2427365364-120793770-1838977142-2415799313)";

        assertEquals("S-1-5-21-2427365364-120793770-1838977142-2415799313",
                SddlReplaceRights.firstForeignReplaceGrant(sddl, TRUSTED));
    }

    @Test
    void anInheritOnlyEntryDoesNotApplyToTheObjectItself() {
        String sddl = "D:AI(A;OICIIOID;FA;;;WD)(A;OICIID;FA;;;SY)";

        assertNull(SddlReplaceRights.firstForeignReplaceGrant(sddl, TRUSTED));
    }

    @Test
    void aDenyEntryIsNeverAGrant() {
        assertNull(SddlReplaceRights.firstForeignReplaceGrant("D:(D;;FA;;;WD)(A;;FA;;;SY)", TRUSTED));
    }

    @Test
    void aRightsTokenThatIsNotKnownIsRefusedNotIgnored() {
        assertEquals("S-1-5-21-9-9-9-1500",
                SddlReplaceRights.firstForeignReplaceGrant("D:(A;;ZZ;;;S-1-5-21-9-9-9-1500)", TRUSTED));
    }

    @Test
    void textThatIsNotASddlIsRefused() {
        assertEquals("unreadable", SddlReplaceRights.firstForeignReplaceGrant("not an acl", TRUSTED));
        assertEquals("unreadable", SddlReplaceRights.firstForeignReplaceGrant("D:(A;;FA", TRUSTED));
    }

    @Test
    void aLauncherInsideMinosHomeIsRefused() {
        Path home = Path.of("minos-home").toAbsolutePath();

        assertThrows(IOException.class,
                () -> SandboxLauncherScript.requireOutsideMinosHome(home.resolve("sandbox").resolve("x.ps1"), home));
        assertThrows(IOException.class,
                () -> SandboxLauncherScript.requireOutsideMinosHome(home.resolve("a").resolve("..").resolve("x.ps1"), home));
    }

    // ---- second review: entries the first reader could not see

    @Test
    void aConditionalEntryOfEveryoneIsNotInvisible() {
        String sddl = "D:AI(XA;OICI;0x1301bf;;;WD;(Member_of {SID(WD)}))";

        assertNotNull(SddlReplaceRights.firstForeignReplaceGrant(sddl, TRUSTED),
                "a conditional ALLOW is an ALLOW the reader does not understand: refuse");
    }

    @Test
    void parenthesesInsideAConditionDoNotCutTheEntriesApart() {
        String sddl = "D:AI(A;OICIID;FA;;;SY)(XA;OICI;0x1301bf;;;WD;(Member_of {SID(WD)}))(A;OICIID;FA;;;BA)";

        assertNotNull(SddlReplaceRights.firstForeignReplaceGrant(sddl, TRUSTED));
    }

    @Test
    void anObjectEntryIsRefusedWhateverItsRights() {
        assertNotNull(SddlReplaceRights.firstForeignReplaceGrant("D:AI(OA;;FA;;;WD)", TRUSTED));
    }

    @Test
    void aNullDaclMeansEveryoneHasFullAccessAndIsRefused() {
        assertNotNull(SddlReplaceRights.firstForeignReplaceGrant("D:PAINO_ACCESS_CONTROL", TRUSTED));
        assertNotNull(SddlReplaceRights.firstForeignReplaceGrant("D:NO_ACCESS_CONTROL", TRUSTED));
    }

    @Test
    void anEmptyDaclGrantsNothingToAnyone() {
        assertNull(SddlReplaceRights.firstForeignReplaceGrant("D:PAI", TRUSTED));
    }

    @Test
    void inheritanceFlagsAreReadTwoCharactersAtATimeNotBySubstring() {
        // CI + OI: the letters "IO" appear across the two flags, but this is not an inherit-only entry.
        assertEquals("WD", SddlReplaceRights.firstForeignReplaceGrant("D:AI(A;CIOI;FA;;;WD)", TRUSTED));
        // A flag this reader does not know is refused, not guessed.
        assertNotNull(SddlReplaceRights.firstForeignReplaceGrant("D:AI(A;OIX;FA;;;SY)", TRUSTED));
    }

    @Test
    void aParentOwnedByAnUntrustedPrincipalIsRefused() {
        UserPrincipal stranger = new User("stranger");
        UserPrincipal admins = new User("Administrators");
        java.util.List<java.nio.file.attribute.AclEntry> acl = java.util.List.of(
                entry(admins), entry(stranger));
        String sddl = "D:AI(A;OICIID;FA;;;BA)(A;OICIID;0x1200a9;;;S-1-5-21-9-9-9-1500)";

        assertFalse(SddlReplaceRights.ownerTrusted(stranger, false, acl, sddl, TRUSTED));
        assertTrue(SddlReplaceRights.ownerTrusted(admins, false, acl, sddl, TRUSTED),
                "the owner maps to BA through the entry at the same position");
        assertTrue(SddlReplaceRights.ownerTrusted(stranger, true, acl, sddl, TRUSTED),
                "the principal this process runs as is trusted by definition");
    }

    @Test
    void anOwnerWithoutAnyEntryCannotBeMappedAndIsRefused() {
        UserPrincipal unknown = new User("unknown");
        java.util.List<java.nio.file.attribute.AclEntry> acl = java.util.List.of(entry(new User("Administrators")));

        assertFalse(SddlReplaceRights.ownerTrusted(unknown, false, acl, "D:AI(A;OICIID;FA;;;BA)", TRUSTED));
    }

    @Test
    void anAclThatDoesNotLineUpWithItsSddlIsNotTrusted() {
        UserPrincipal admins = new User("Administrators");
        java.util.List<java.nio.file.attribute.AclEntry> acl = java.util.List.of(entry(admins));

        assertFalse(SddlReplaceRights.ownerTrusted(admins, false, acl, "D:AI(A;;FA;;;BA)(A;;FA;;;SY)", TRUSTED));
    }

    private record User(String getName) implements UserPrincipal { }

    private static java.nio.file.attribute.AclEntry entry(UserPrincipal who) {
        return java.nio.file.attribute.AclEntry.newBuilder()
                .setType(java.nio.file.attribute.AclEntryType.ALLOW)
                .setPrincipal(who)
                .setPermissions(java.nio.file.attribute.AclEntryPermission.READ_DATA)
                .build();
    }
}
