package com.minos.discovery;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Rend un fichier régulier illisible pour l'utilisateur courant, sous POSIX (permissions vides) comme sous Windows
 * (entrée ACL de refus de lecture), pour les tests qui ont besoin d'un fichier qui existe mais ne s'ouvre pas.
 */
final class UnreadableFile {

    private UnreadableFile() { }

    /** Vrai si le fichier est désormais illisible ; faux (ex. administrateur, système sans droits) : à ignorer par le test. */
    static boolean deny(Path file) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        if (posix != null) {
            posix.setPermissions(Set.of());
        } else {
            AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
            if (acl == null) return false;
            UserPrincipal user;
            try {
                user = file.getFileSystem().getUserPrincipalLookupService()
                        .lookupPrincipalByName(System.getProperty("user.name"));
            } catch (IOException unknown) {
                user = acl.getOwner();
            }
            List<AclEntry> entries = new ArrayList<>(acl.getAcl());
            entries.addFirst(AclEntry.newBuilder()
                    .setType(AclEntryType.DENY)
                    .setPrincipal(user)
                    .setPermissions(AclEntryPermission.READ_DATA)
                    .build());
            acl.setAcl(entries);
        }
        try (var ignored = Files.newByteChannel(file, StandardOpenOption.READ)) {
            return false;
        } catch (IOException denied) {
            return true;
        }
    }
}
