package com.minos.runtime.local;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Reads the DACL of a directory, as the SDDL text {@code icacls /save} writes, and says whether a
 * principal outside a trusted set can <em>replace what is under it</em>: delete a child or the object,
 * rewrite its DACL or take its ownership. SDDL carries SIDs, so the answer does not depend on the
 * language of the machine (unlike an account name: Everyone is "Tout le monde" on a French one).
 *
 * <p>Inherit-only entries do not apply to the object itself and are skipped. Only ALLOW entries count: a
 * DENY can only narrow, and ignoring it errs on the side of refusing. A rights token this reader does not
 * know is treated as dangerous, never as harmless.</p>
 */
final class SddlReplaceRights {

    /** DELETE_CHILD, DELETE, WRITE_DAC, WRITE_OWNER, GENERIC_ALL, GENERIC_WRITE. */
    private static final long REPLACE_MASK = 0x40L | 0x10000L | 0x40000L | 0x80000L | 0x10000000L | 0x40000000L;

    private static final Map<String, Long> RIGHTS = Map.ofEntries(
            Map.entry("FA", 0x1f01ffL), Map.entry("FR", 0x120089L), Map.entry("FW", 0x120116L),
            Map.entry("FX", 0x1200a0L), Map.entry("GA", 0x10000000L), Map.entry("GR", 0x80000000L),
            Map.entry("GW", 0x40000000L), Map.entry("GX", 0x20000000L), Map.entry("RC", 0x20000L),
            Map.entry("SD", 0x10000L), Map.entry("WD", 0x40000L), Map.entry("WO", 0x80000L));

    private static final Map<String, String> ALIASES = Map.of(
            "SY", "S-1-5-18", "BA", "S-1-5-32-544", "CO", "S-1-3-0", "OW", "S-1-3-4");

    private SddlReplaceRights() {
    }

    /**
     * The SID of the first ALLOW entry that lets a principal outside {@code trustedSids} replace what is
     * under the object, or {@code null}. A SDDL that cannot be read at all is reported as an entry named
     * {@code "unreadable"}: the caller refuses, it never guesses.
     */
    static String firstForeignReplaceGrant(String sddl, Set<String> trustedSids) {
        Objects.requireNonNull(sddl, "sddl");
        Objects.requireNonNull(trustedSids, "trustedSids");
        int start = sddl.indexOf("D:");
        if (start < 0) return "unreadable";
        int end = sddl.indexOf("S:", start);
        String dacl = end < 0 ? sddl.substring(start + 2) : sddl.substring(start + 2, end);
        int index = dacl.indexOf('(');
        if (index < 0) return null; // no entry at all: nobody is granted anything
        while (index >= 0 && index < dacl.length()) {
            int close = dacl.indexOf(')', index);
            if (close < 0) return "unreadable";
            String[] fields = dacl.substring(index + 1, close).split(";", -1);
            index = dacl.indexOf('(', close);
            if (fields.length < 6) return "unreadable";
            if (!"A".equals(fields[0]) || fields[1].contains("IO")) continue;
            long mask = mask(fields[2]);
            if (mask < 0) return fields[5].isEmpty() ? "unreadable" : fields[5];
            if ((mask & REPLACE_MASK) == 0) continue;
            String sid = ALIASES.getOrDefault(fields[5], fields[5]);
            if (!trustedSids.contains(sid)) return sid;
        }
        return null;
    }

    /** The access mask of a rights field, or -1 when it holds a token this reader does not know. */
    private static long mask(String rights) {
        try {
            if (rights.startsWith("0x") || rights.startsWith("0X")) return Long.parseLong(rights.substring(2), 16);
        } catch (NumberFormatException unreadable) {
            return -1;
        }
        long mask = 0;
        if (rights.length() % 2 != 0) return -1;
        for (int i = 0; i < rights.length(); i += 2) {
            Long value = RIGHTS.get(rights.substring(i, i + 2));
            if (value == null) return -1;
            mask |= value;
        }
        return mask;
    }
}
