package com.minos.testsupport;

import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Rend un <strong>répertoire</strong> illisible pour l'utilisateur courant, sous POSIX (permissions vides) comme sous
 * Windows (entrée ACL de refus de listage), pour les tests qui ont besoin d'un répertoire qui existe mais que le
 * parcours ne peut pas ouvrir (un volume Docker en 0700 appartenant à root, par exemple).
 *
 * <p>Le résultat est contrôlé : le répertoire est « illisible » seulement si {@code Files.newDirectoryStream} échoue
 * réellement. Un compte root (Linux) ou administrateur élevé (Windows) peut encore le lister : le test s'ignore alors
 * ({@link #denyOrSkip}), il n'est jamais vert par défaut. La restauration est dans {@link #close()} : sans elle
 * {@code @TempDir} ne pourrait pas nettoyer le répertoire.</p>
 */
public final class UnreadableDirectory implements AutoCloseable {

    private final Path directory;
    private final Set<PosixFilePermission> posixBefore;
    private final List<AclEntry> aclBefore;
    private final boolean denied;

    private UnreadableDirectory(
            Path directory,
            Set<PosixFilePermission> posixBefore,
            List<AclEntry> aclBefore,
            boolean denied
    ) {
        this.directory = directory;
        this.posixBefore = posixBefore;
        this.aclBefore = aclBefore;
        this.denied = denied;
    }

    /** Refuse le listage ; {@link #denied()} dit si le répertoire est réellement devenu illisible. */
    public static UnreadableDirectory deny(Path directory) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(directory, PosixFileAttributeView.class);
        UnreadableDirectory value;
        if (posix != null) {
            Set<PosixFilePermission> before = posix.readAttributes().permissions();
            posix.setPermissions(Set.of());
            value = new UnreadableDirectory(directory, before, null, false);
        } else {
            AclFileAttributeView acl = Files.getFileAttributeView(directory, AclFileAttributeView.class);
            if (acl == null) return new UnreadableDirectory(directory, null, null, false);
            List<AclEntry> before = List.copyOf(acl.getAcl());
            UserPrincipal user;
            try {
                user = directory.getFileSystem().getUserPrincipalLookupService()
                        .lookupPrincipalByName(System.getProperty("user.name"));
            } catch (IOException unknown) {
                user = acl.getOwner();
            }
            List<AclEntry> entries = new ArrayList<>(before);
            entries.addFirst(AclEntry.newBuilder()
                    .setType(AclEntryType.DENY)
                    .setPrincipal(user)
                    .setPermissions(AclEntryPermission.LIST_DIRECTORY)
                    .build());
            acl.setAcl(entries);
            value = new UnreadableDirectory(directory, null, before, false);
        }
        return new UnreadableDirectory(directory, value.posixBefore, value.aclBefore, listingFails(directory));
    }

    /** Comme {@link #deny} ; ignore le test appelant (compte root ou administrateur) si le répertoire reste lisible. */
    public static UnreadableDirectory denyOrSkip(Path directory) throws IOException {
        UnreadableDirectory value = deny(directory);
        if (!value.denied) {
            value.close();
            Assumptions.assumeTrue(false, "this account can still list the directory (root or elevated administrator)");
        }
        return value;
    }

    public boolean denied() {
        return denied;
    }

    private static boolean listingFails(Path directory) {
        try (DirectoryStream<Path> ignored = Files.newDirectoryStream(directory)) {
            return false;
        } catch (IOException denied) {
            return true;
        }
    }

    @Override
    public void close() throws IOException {
        if (posixBefore != null) {
            Files.getFileAttributeView(directory, PosixFileAttributeView.class).setPermissions(posixBefore);
        } else if (aclBefore != null) {
            Files.getFileAttributeView(directory, AclFileAttributeView.class).setAcl(aclBefore);
        }
    }
}
