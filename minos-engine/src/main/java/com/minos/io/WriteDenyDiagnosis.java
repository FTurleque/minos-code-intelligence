package com.minos.io;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Words an access-denied failure of private storage when an explicit DENY entry (Windows) takes a write right from
 * the owner: MINOS says why it cannot write, never which path. Used only to word a diagnostic, never to decide access.
 */
final class WriteDenyDiagnosis {

    private static final String WRITE_PROTECTED_MESSAGE =
            "private storage is write-protected by an explicit deny entry; MINOS does not remove it";

    private static final Set<AclEntryPermission> WRITE_RIGHTS = EnumSet.of(
            AclEntryPermission.WRITE_DATA,
            AclEntryPermission.APPEND_DATA,
            AclEntryPermission.DELETE,
            AclEntryPermission.DELETE_CHILD,
            AclEntryPermission.WRITE_ATTRIBUTES,
            AclEntryPermission.WRITE_NAMED_ATTRS,
            AclEntryPermission.WRITE_ACL);

    private WriteDenyDiagnosis() {
    }

    /**
     * The failure for an access-denied error on a location whose DACL carries a write DENY that applies to
     * the owner: MINOS says why it cannot write, and the message does not name the path. The original
     * error names it, so what is chained is an access-denied cause without its path.
     */
    static IOException explainDenied(AccessDeniedException denied, List<AclEntry> aclOfTheLocation,
                                     UserPrincipal owner) {
        return carriesWriteDenyForOwner(aclOfTheLocation, owner) ? writeProtected() : denied;
    }

    /**
     * Rethrows {@code denied} as the write-protected failure when one of {@code locations} carries a write DENY.
     * {@code aclView} gives the ACL view of a location, or {@code null} when it has none.
     */
    static IOException explainDenied(AccessDeniedException denied, Function<Path, AclFileAttributeView> aclView,
                                     Path... locations) {
        for (Path location : locations) {
            if (location == null) continue;
            AclFileAttributeView view = aclView.apply(location);
            if (view == null) continue;
            try {
                if (carriesWriteDenyForOwner(view.getAcl(), view.getOwner())) return writeProtected();
            } catch (IOException unreadable) {
                // Nothing more precise than the original refusal can be said.
            }
        }
        return denied;
    }

    private static IOException writeProtected() {
        return new IOException(WRITE_PROTECTED_MESSAGE, new AccessDeniedException(null));
    }

    /**
     * Whether a DENY entry that applies to the object itself takes a write right from the owner. An entry
     * counts when it is not inherit-only and names the owner, or a group: Java cannot say which groups the
     * process belongs to, so a DENY on a group is assumed to apply. That is wrong only when the owner is
     * not a member, and then the refusal had another cause that the message would mislabel.
     */
    static boolean carriesWriteDenyForOwner(List<AclEntry> acl, UserPrincipal owner) {
        for (AclEntry entry : acl) {
            if (entry.type() != AclEntryType.DENY) continue;
            if (entry.flags().contains(AclEntryFlag.INHERIT_ONLY)) continue;
            boolean applies = entry.principal().equals(owner)
                    || entry.principal() instanceof GroupPrincipal
                    || namesTheProcessUser(entry.principal());
            if (!applies) continue;
            for (AclEntryPermission right : entry.permissions()) {
                if (WRITE_RIGHTS.contains(right)) return true;
            }
        }
        return false;
    }

    /**
     * Whether {@code principal} is the account this process runs as. An elevated token owns what it creates
     * as the Administrators group, not as the user, so a DENY placed on the user never equals the owner.
     * Used only to word a diagnostic, never to decide access: the name is compared case-insensitively
     * after its domain part.
     */
    private static boolean namesTheProcessUser(UserPrincipal principal) {
        String user = System.getProperty("user.name");
        if (user == null || user.isBlank()) return false;
        String name = principal.getName();
        int separator = name.lastIndexOf('\\');
        String account = separator >= 0 ? name.substring(separator + 1) : name;
        return account.equalsIgnoreCase(user);
    }
}
