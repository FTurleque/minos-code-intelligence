package com.minos.runtime.local;

import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Reads the DACL of a directory, as the SDDL text {@code icacls /save} writes, and says whether a
 * principal outside a trusted set can <em>replace what is under it</em>: delete a child or the object,
 * rewrite its DACL or take its ownership. SDDL carries SIDs, so the answer does not depend on the
 * language of the machine (unlike an account name: Everyone is "Tout le monde" on a French one).
 *
 * <p>The reader refuses what it does not understand, never ignores it: a DACL that is null
 * ({@code NO_ACCESS_CONTROL}: everyone has full access), an entry of any type other than ALLOW ({@code A})
 * or DENY ({@code D}) (a conditional {@code XA} entry is an ALLOW whose condition this reader does not
 * evaluate; an object entry {@code OA} is not meant for files), an inheritance flag it does not know, a
 * rights token it does not know, or text that does not parse. Entries are cut apart respecting the nesting
 * of parentheses, because a condition contains some.</p>
 *
 * <p>Inherit-only entries do not apply to the object itself and are skipped. A DENY can only narrow, and
 * ignoring it errs on the side of refusing.</p>
 */
final class SddlReplaceRights {

    /** The verdict for text that cannot be read: a refusal, like any other offender. */
    static final String UNREADABLE = "unreadable";

    /** DELETE_CHILD, DELETE, WRITE_DAC, WRITE_OWNER, GENERIC_ALL, GENERIC_WRITE. */
    private static final long REPLACE_MASK = 0x40L | 0x10000L | 0x40000L | 0x80000L | 0x10000000L | 0x40000000L;

    private static final Map<String, Long> RIGHTS = Map.ofEntries(
            Map.entry("FA", 0x1f01ffL), Map.entry("FR", 0x120089L), Map.entry("FW", 0x120116L),
            Map.entry("FX", 0x1200a0L), Map.entry("GA", 0x10000000L), Map.entry("GR", 0x80000000L),
            Map.entry("GW", 0x40000000L), Map.entry("GX", 0x20000000L), Map.entry("RC", 0x20000L),
            Map.entry("SD", 0x10000L), Map.entry("WD", 0x40000L), Map.entry("WO", 0x80000L));

    private static final Set<String> INHERITANCE_FLAGS = Set.of("OI", "CI", "IO", "NP", "ID", "SA", "FA");

    private static final Map<String, String> ALIASES = Map.of(
            "SY", "S-1-5-18", "BA", "S-1-5-32-544", "CO", "S-1-3-0", "OW", "S-1-3-4");

    private SddlReplaceRights() {
    }

    /** One ALLOW or DENY entry. */
    private record Ace(String type, boolean inheritOnly, String rights, String sid) {
        boolean allow() {
            return "A".equals(type);
        }
    }

    /**
     * The SID of the first ALLOW entry that lets a principal outside {@code trustedSids} replace what is
     * under the object, or {@link #UNREADABLE} when the DACL cannot be understood, or {@code null}.
     */
    static String firstForeignReplaceGrant(String sddl, Set<String> trustedSids) {
        Objects.requireNonNull(trustedSids, "trustedSids");
        List<Ace> aces = parse(sddl);
        if (aces == null) return UNREADABLE;
        for (Ace ace : aces) {
            if (!ace.allow() || ace.inheritOnly()) continue;
            long mask = mask(ace.rights());
            if (mask < 0) return ace.sid().isEmpty() ? UNREADABLE : ace.sid();
            if ((mask & REPLACE_MASK) == 0) continue;
            String sid = ALIASES.getOrDefault(ace.sid(), ace.sid());
            if (!trustedSids.contains(sid)) return sid;
        }
        return null;
    }

    /**
     * Whether the owner of a directory is trusted: it is the principal this process runs as, or it is
     * named by an entry whose SID is trusted. Java gives an owner as a principal with no SID, so the SID is
     * found through the entry of {@code javaAcl} that names the same principal, matched by position with the
     * entry of the SDDL (both list the same ALLOW and DENY entries in the same order). An owner that has
     * no entry, or an ACL that does not line up with its SDDL, cannot be identified and is not trusted.
     */
    static boolean ownerTrusted(UserPrincipal owner, boolean ownerIsCurrentUser,
                                List<AclEntry> javaAcl, String sddl, Set<String> trustedSids) {
        if (ownerIsCurrentUser) return true;
        List<Ace> aces = parse(sddl);
        if (aces == null || aces.size() != javaAcl.size()) return false;
        for (int index = 0; index < aces.size(); index++) {
            if (!javaAcl.get(index).principal().equals(owner)) continue;
            String sid = ALIASES.getOrDefault(aces.get(index).sid(), aces.get(index).sid());
            if (trustedSids.contains(sid)) return true;
        }
        return false;
    }

    /** The entries of the DACL, or {@code null} when it is null, unreadable or holds an entry of another type. */
    private static List<Ace> parse(String sddl) {
        Objects.requireNonNull(sddl, "sddl");
        String line = sddl.lines().filter(candidate -> candidate.startsWith("D:")).findFirst().orElse(null);
        if (line == null) return null;
        int index = 2;
        while (index < line.length() && line.charAt(index) != '(' && !line.startsWith("S:", index)) index++;
        if (line.substring(2, index).contains("NO_ACCESS_CONTROL")) return null;
        List<Ace> aces = new ArrayList<>();
        while (index < line.length() && line.charAt(index) == '(') {
            int close = matchingParenthesis(line, index);
            if (close < 0) return null;
            Ace ace = ace(line.substring(index + 1, close));
            if (ace == null) return null;
            aces.add(ace);
            index = close + 1;
        }
        // What follows the entries is either nothing or the SACL; anything else is not a DACL.
        return index >= line.length() || line.startsWith("S:", index) ? aces : null;
    }

    private static Ace ace(String text) {
        // Type;Flags;Rights;ObjectGuid;InheritedObjectGuid;Sid[;condition and resource attributes]
        String[] fields = text.split(";", 7);
        if (fields.length < 6) return null;
        String type = fields[0];
        if (!"A".equals(type) && !"D".equals(type)) return null;
        String flags = fields[1];
        if (flags.length() % 2 != 0) return null;
        boolean inheritOnly = false;
        for (int i = 0; i < flags.length(); i += 2) {
            String flag = flags.substring(i, i + 2);
            if (!INHERITANCE_FLAGS.contains(flag)) return null;
            if ("IO".equals(flag)) inheritOnly = true;
        }
        return new Ace(type, inheritOnly, fields[2], fields[5]);
    }

    /** The index of the parenthesis that closes the one at {@code open}, ignoring quoted text; -1 if none. */
    private static int matchingParenthesis(String text, int open) {
        int depth = 0;
        boolean quoted = false;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') quoted = !quoted;
            if (quoted) continue;
            if (c == '(') depth++;
            if (c == ')' && --depth == 0) return i;
        }
        return -1;
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
