package com.minos.runtime.local;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
}
