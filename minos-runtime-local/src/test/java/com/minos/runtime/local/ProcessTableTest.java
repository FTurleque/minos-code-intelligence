package com.minos.runtime.local;

import com.minos.runtime.local.CgroupJobOwnership.Namespaces;
import com.minos.runtime.local.CgroupJobOwnership.OwnerLookup;
import com.minos.runtime.local.CgroupJobOwnership.OwnerStatus;
import com.minos.runtime.local.CgroupJobOwnership.OwnerStatus.Presence;
import com.minos.runtime.local.CgroupJobOwnership.ProcessTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * "This owner is gone" is a claim about the process table, so it is only made about a table proven
 * readable and proven to be the one of this PID namespace. Everything else the table can say (cannot be
 * read, belongs to another PID namespace, hides the processes of other accounts, answers partially) is
 * "cannot verify", which never reclaims anything.
 *
 * <p>Host-independent: the table is a temporary directory laid out like {@code /proc}.</p>
 */
class ProcessTableTest {

    private static final long OWN_PID = 4_242L;
    private static final long OWNER_PID = 7_777L;
    private static final String MOUNTINFO_PLAIN =
            "27 22 0:23 / /proc rw,nosuid,nodev,noexec,relatime shared:14 - proc proc rw\n";
    private static final String MOUNTINFO_HIDEPID =
            "27 22 0:23 / /proc rw,nosuid,nodev,noexec,relatime shared:14 - proc proc rw,hidepid=2\n";
    private static final String MOUNTINFO_HIDEPID_NAMED =
            "27 22 0:23 / /proc rw,nosuid,nodev,noexec,relatime shared:14 - proc proc rw,hidepid=invisible,gid=1001\n";
    private static final String MOUNTINFO_HIDEPID_OFF =
            "27 22 0:23 / /proc rw,nosuid,nodev,noexec,relatime shared:14 - proc proc rw,hidepid=off\n";

    @Test
    void aLiveOwnerIsPresentWithItsKernelStartTicks(@TempDir Path proc) throws Exception {
        readableTable(proc, MOUNTINFO_PLAIN);
        process(proc, OWNER_PID, stat(OWNER_PID, "java", 2_345_678L));

        OwnerStatus status = table(proc, false).find(OWNER_PID);

        assertEquals(Presence.PRESENT, status.presence(), status.reason());
        assertEquals(OptionalLong.of(2_345_678L), status.startTicks());
    }

    @Test
    void aPidAbsentFromAReadableTableIsGone(@TempDir Path proc) throws Exception {
        readableTable(proc, MOUNTINFO_PLAIN);

        OwnerStatus status = table(proc, false).find(OWNER_PID);

        assertEquals(Presence.GONE, status.presence(), status.reason());
    }

    @Test
    void anUnreadableTableNeverProvesAnOwnerDead(@TempDir Path proc) throws Exception {
        // No entry for this process itself: the table is not readable, so its silence proves nothing.
        OwnerStatus status = table(proc, true).find(OWNER_PID);

        assertEquals(Presence.UNVERIFIABLE, status.presence(), "an empty table is not a table: " + status);
        assertTrue(status.reason().contains("process table"), status.reason());
    }

    @Test
    void aTableOfAnotherPidNamespaceProvesNothing(@TempDir Path proc) throws Exception {
        // /proc mounted from the parent namespace: "self" there carries another PID than ours.
        Files.createDirectories(proc.resolve("self"));
        Files.writeString(proc.resolve("self").resolve("stat"), stat(9_999L, "java", 1_000L), StandardCharsets.UTF_8);
        Files.writeString(proc.resolve("self").resolve("mountinfo"), MOUNTINFO_PLAIN, StandardCharsets.UTF_8);

        OwnerStatus status = table(proc, true).find(OWNER_PID);

        assertEquals(Presence.UNVERIFIABLE, status.presence(), "the pid of another table designates another process");
    }

    @Test
    void aStatThatCannotBeReadIsUnverifiableNotGone(@TempDir Path proc) throws Exception {
        readableTable(proc, MOUNTINFO_PLAIN);
        Files.createDirectories(proc.resolve(Long.toString(OWNER_PID)).resolve("stat"));

        OwnerStatus status = table(proc, true).find(OWNER_PID);

        assertEquals(Presence.UNVERIFIABLE, status.presence(), "a read failure is not an absence: " + status);
    }

    @Test
    void aProcessDirectoryWithoutStatIsUnverifiableNotGone(@TempDir Path proc) throws Exception {
        readableTable(proc, MOUNTINFO_PLAIN);
        Files.createDirectories(proc.resolve(Long.toString(OWNER_PID)));

        OwnerStatus status = table(proc, true).find(OWNER_PID);

        assertEquals(Presence.UNVERIFIABLE, status.presence(), "the process exists, its stat is missing: " + status);
    }

    @Test
    void aMalformedStatOfAPresentProcessIsPresentWithUnknownTicks(@TempDir Path proc) throws Exception {
        readableTable(proc, MOUNTINFO_PLAIN);
        process(proc, OWNER_PID, "not a stat record at all");

        OwnerStatus status = table(proc, false).find(OWNER_PID);

        assertEquals(Presence.PRESENT, status.presence(), "the directory exists: the process is there");
        assertEquals(OptionalLong.empty(), status.startTicks(), "but its start is unknown");
    }

    @Test
    void aTruncatedStatOfAPresentProcessIsPresentWithUnknownTicks(@TempDir Path proc) throws Exception {
        readableTable(proc, MOUNTINFO_PLAIN);
        String whole = stat(OWNER_PID, "java", 2_345_678L);
        process(proc, OWNER_PID, whole.substring(0, whole.indexOf("2345678") + 3));

        OwnerStatus status = table(proc, false).find(OWNER_PID);

        assertEquals(Presence.PRESENT, status.presence(), status.reason());
        assertEquals(OptionalLong.empty(), status.startTicks(), "a cut field 22 is unknown, never a smaller number");
    }

    @Test
    void anAbsentPidIsNotProvenDeadWhenTheTableHidesOtherAccountsAndTheOwnerMayBeOne(@TempDir Path proc)
            throws Exception {
        for (String mountinfo : new String[] {MOUNTINFO_HIDEPID, MOUNTINFO_HIDEPID_NAMED}) {
            readableTable(proc, mountinfo);

            OwnerStatus status = table(proc, false).find(OWNER_PID);

            assertEquals(Presence.UNVERIFIABLE, status.presence(), mountinfo + " -> " + status);
            assertTrue(status.reason().contains("hidepid"), status.reason());
        }
    }

    @Test
    void anAbsentPidIsProvenDeadWhenTheTableHidesOtherAccountsButTheOwnerIsThisAccount(@TempDir Path proc)
            throws Exception {
        readableTable(proc, MOUNTINFO_HIDEPID);

        OwnerStatus status = table(proc, true).find(OWNER_PID);

        assertEquals(Presence.GONE, status.presence(), "an account always sees its own processes: " + status);
    }

    @Test
    void aTableThatHidesNothingProvesAnAbsenceWhoeverTheOwnerIs(@TempDir Path proc) throws Exception {
        for (String mountinfo : new String[] {MOUNTINFO_PLAIN, MOUNTINFO_HIDEPID_OFF}) {
            readableTable(proc, mountinfo);

            OwnerStatus status = table(proc, false).find(OWNER_PID);

            assertEquals(Presence.GONE, status.presence(), mountinfo + " -> " + status);
        }
    }

    @Test
    void aMountTableThatCannotBeReadLeavesTheVisibilityOfOtherAccountsInDoubt(@TempDir Path proc) throws Exception {
        Files.createDirectories(proc.resolve("self"));
        Files.writeString(proc.resolve("self").resolve("stat"), stat(OWN_PID, "java", 1_000L), StandardCharsets.UTF_8);

        OwnerStatus status = table(proc, false).find(OWNER_PID);

        assertEquals(Presence.UNVERIFIABLE, status.presence(), status.toString());
    }

    @Test
    void aPresentProcessIsPresentWhateverTheTableHides(@TempDir Path proc) throws Exception {
        readableTable(proc, MOUNTINFO_HIDEPID);
        process(proc, OWNER_PID, stat(OWNER_PID, "java", 2_345_678L));

        assertEquals(Presence.PRESENT, table(proc, false).find(OWNER_PID).presence());
    }

    @Test
    void namespacesAreReadFromTheLinksOfTheProcessAndUnknownWhenTheyCannotBe(@TempDir Path proc) throws Exception {
        Path namespaces = Files.createDirectories(proc.resolve("7777").resolve("ns"));
        boolean links = tryLink(namespaces.resolve("pid"), "pid:[4026531836]")
                && tryLink(namespaces.resolve("time"), "time:[4026531834]");
        assumeTrue(links, "this host cannot create the symbolic links /proc uses for namespaces");

        assertEquals(new Namespaces(4_026_531_836L, 4_026_531_834L), Namespaces.read(proc, 7_777L));
        assertEquals(Namespaces.UNKNOWN, Namespaces.read(proc, 8_888L), "no such process");
        Files.delete(namespaces.resolve("pid"));
        assertEquals(Namespaces.UNKNOWN, Namespaces.read(proc, 7_777L), "an unreadable PID namespace is unknown");
    }

    @Test
    void theTimeNamespaceIsZeroOnAKernelThatHasNone(@TempDir Path proc) throws Exception {
        Path namespaces = Files.createDirectories(proc.resolve("7777").resolve("ns"));
        assumeTrue(tryLink(namespaces.resolve("pid"), "pid:[4026531836]"),
                "this host cannot create the symbolic links /proc uses for namespaces");

        assertEquals(new Namespaces(4_026_531_836L, 0L), Namespaces.read(proc, 7_777L));
    }

    /** Real kernel, real {@code /proc}: the answers the decision relies on. */
    @Test
    @EnabledOnOs(OS.LINUX)
    void theRealProcessTableAnswersForALiveAndForAnAbsentProcess(@TempDir Path cgroup) throws Exception {
        OwnerLookup system = OwnerLookup.system(cgroup);
        long self = ProcessHandle.current().pid();

        OwnerStatus live = system.find(self);
        OwnerStatus gone = system.find(absentPid());

        assertEquals(Presence.PRESENT, live.presence(), live.reason());
        assertEquals(CgroupJobOwnership.startTicks(CgroupJobOwnership.PROC, self), live.startTicks());
        assertEquals(Presence.GONE, gone.presence(), gone.reason());
        assertTrue(Namespaces.read(CgroupJobOwnership.PROC, self).known(), "/proc/self/ns/pid is readable on Linux");
    }

    private static ProcessTable table(Path proc, boolean ownerIsThisAccount) {
        return new ProcessTable(proc, OWN_PID, () -> ownerIsThisAccount);
    }

    /** A table proven readable: this process itself is listed, with the PID it believes it has. */
    private static void readableTable(Path proc, String mountinfo) throws IOException {
        Files.createDirectories(proc.resolve("self"));
        Files.writeString(proc.resolve("self").resolve("stat"), stat(OWN_PID, "java", 1_000L), StandardCharsets.UTF_8);
        Files.writeString(proc.resolve("self").resolve("mountinfo"), mountinfo, StandardCharsets.UTF_8);
    }

    private static void process(Path proc, long pid, String stat) throws IOException {
        Files.createDirectories(proc.resolve(Long.toString(pid)));
        Files.writeString(proc.resolve(Long.toString(pid)).resolve("stat"), stat, StandardCharsets.ISO_8859_1);
    }

    /** A complete {@code /proc/<pid>/stat} record: field 22 is {@code startTicks}. */
    private static String stat(long pid, String comm, long startTicks) {
        return pid + " (" + comm + ") S 1 " + pid + " " + pid + " 0 -1 4194560 1234 0 0 0 7 3 0 0 20 0 29 0 "
                + startTicks + " 123456789 4321 18446744073709551615 1 1 0 0 0 0 0 4096 0 0 0 0 17 3 0 0\n";
    }

    private static boolean tryLink(Path link, String target) {
        try {
            Files.createSymbolicLink(link, Path.of(target));
            return true;
        } catch (IOException | RuntimeException unsupported) {
            return false;
        }
    }

    private static long absentPid() {
        for (long pid = 2_147_000_001L; pid > 1L; pid -= 7_919L) {
            if (ProcessHandle.of(pid).isEmpty()) return pid;
        }
        throw new AssertionError("no free pid found");
    }
}
